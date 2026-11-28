@file:Suppress("DEPRECATION")

package com.example.voiceanswer

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import kotlin.math.min

/**
 * Рингтон входящего звонка. Приложение объявляет в манифесте, что звонит само
 * (IN_CALL_SERVICE_RINGING), поэтому именно здесь мелодия проигрывается с нарастающей громкостью:
 * от тихой до полной за выбранное в настройках время.
 */
object Ringer {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var fallback: Ringtone? = null
    private var startedAt = 0L
    private var rampMs = 0L
    private var maxGain = 1f

    var playing = false
        private set

    private val stepper = object : Runnable {
        override fun run() {
            val g = currentGain()
            val p = player
            try {
                if (p != null) {
                    p.setVolume(g, g)
                } else {
                    val f = fallback ?: return
                    if (Build.VERSION.SDK_INT >= 28) f.volume = g else return
                }
            } catch (_: Exception) {
            }
            if (SystemClock.elapsedRealtime() - startedAt < rampMs) handler.postDelayed(this, 40)
        }
    }

    private fun currentGain(): Float {
        if (rampMs <= 0L) return maxGain
        val t = min(1f, (SystemClock.elapsedRealtime() - startedAt).toFloat() / rampMs)
        // от -26 дБ (слышно, но тихо) до полной громкости с равномерным ростом в децибелах
        val g = 0.05f * Math.pow(20.0, t.toDouble()).toFloat()
        return min(1f, g) * maxGain
    }

    /** Нужно ли звонить при текущем режиме «Не беспокоить». [known] — звонящий есть в контактах. */
    private fun allowedByDnd(ctx: Context, known: Boolean): Boolean = try {
        when (ctx.getSystemService(NotificationManager::class.java).currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_NONE, NotificationManager.INTERRUPTION_FILTER_ALARMS -> false
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> known
            else -> true
        }
    } catch (e: Exception) {
        true
    }

    /** Личный рингтон абонента: из настроек приложения, иначе из контакта телефона. null — стандартный. */
    fun customFor(ctx: Context, number: String): String? {
        val key = Repo.numKey(number)
        if (key.isEmpty()) return null
        Prefs.ringFor(ctx, key)?.let { return it }
        try {
            if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val uri = Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
                ctx.contentResolver.query(uri, arrayOf(android.provider.ContactsContract.PhoneLookup.CUSTOM_RINGTONE), null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) {
                        val v = cur.getString(0)
                        if (!v.isNullOrBlank()) return v
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    /** Название мелодии для показа в списке. */
    fun titleOf(ctx: Context, value: String?): String {
        if (value == null) return "Стандартный"
        if (value == "silent") return "Без звука"
        if (value.startsWith("file:")) return "Своя мелодия"
        return try {
            RingtoneManager.getRingtone(ctx, Uri.parse(value))?.getTitle(ctx) ?: "Свой рингтон"
        } catch (e: Exception) {
            "Свой рингтон"
        }
    }

    fun start(ctx: Context, known: Boolean, custom: String? = null) {
        if (playing) return // уже звонит: не сбрасываем нарастание
        stop()
        val app = ctx.applicationContext
        val am = try {
            app.getSystemService(AudioManager::class.java)
        } catch (e: Exception) {
            return
        }
        val mode = am.ringerMode
        if (mode == AudioManager.RINGER_MODE_SILENT) return
        if (!allowedByDnd(app, known)) return

        val vibrateWhenRinging = try {
            Settings.System.getInt(app.contentResolver, "vibrate_when_ringing", 0) == 1
        } catch (e: Exception) {
            false
        }
        if (mode == AudioManager.RINGER_MODE_VIBRATE || vibrateWhenRinging) vibrate(app)
        if (mode != AudioManager.RINGER_MODE_NORMAL) {
            playing = true
            return
        }

        if (custom == "silent") {
            // для этого абонента выбран «без звука»: только вибрация (если включена)
            playing = true
            return
        }
        maxGain = if (Prefs.quietRinger(app)) 0.5f else 1f
        rampMs = if (Prefs.rampRing(app)) Prefs.rampSeconds(app).coerceIn(3, 60) * 1000L else 0L
        startedAt = SystemClock.elapsedRealtime()

        val uris = ArrayList<Uri>()
        if (custom != null) {
            try {
                uris.add(Uri.parse(custom))
            } catch (_: Exception) {
            }
        }
        try {
            val u = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_RINGTONE)
            if (u != null) uris.add(u)
        } catch (_: Exception) {
        }
        try {
            val d = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            if (d != null && !uris.contains(d)) uris.add(d)
        } catch (_: Exception) {
        }
        if (uris.isEmpty()) return

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        // 1) MediaPlayer с плавной громкостью: сначала по ссылке, затем через открытый файл
        for (uri in uris) {
            val mp = makePlayer(app, uri, attrs)
            if (mp != null) {
                try {
                    val g = currentGain()
                    mp.setVolume(g, g)
                    mp.start()
                    player = mp
                    playing = true
                    handler.removeCallbacks(stepper)
                    if (rampMs > 0L) handler.postDelayed(stepper, 40)
                    return
                } catch (e: Exception) {
                    try {
                        mp.release()
                    } catch (_: Exception) {
                    }
                }
            }
        }

        // 2) Запасной путь: обычный рингтон (на Android 9+ громкость тоже нарастает)
        for (uri in uris) {
            try {
                val rt = RingtoneManager.getRingtone(app, uri) ?: continue
                rt.audioAttributes = attrs
                if (Build.VERSION.SDK_INT >= 28) {
                    rt.isLooping = true
                    rt.volume = currentGain()
                }
                rt.play()
                fallback = rt
                playing = true
                handler.removeCallbacks(stepper)
                if (rampMs > 0L && Build.VERSION.SDK_INT >= 28) handler.postDelayed(stepper, 40)
                return
            } catch (_: Exception) {
            }
        }
    }

    private fun makePlayer(app: Context, uri: Uri, attrs: AudioAttributes): MediaPlayer? {
        // по ссылке
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(attrs)
            mp.setDataSource(app, uri)
            mp.isLooping = true
            mp.prepare()
            return mp
        } catch (_: Exception) {
        }
        // через открытый файл (если прямая ссылка недоступна)
        try {
            val afd = app.contentResolver.openAssetFileDescriptor(uri, "r") ?: return null
            afd.use {
                val mp = MediaPlayer()
                mp.setAudioAttributes(attrs)
                mp.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                mp.isLooping = true
                mp.prepare()
                return mp
            }
        } catch (_: Exception) {
        }
        return null
    }

    /** Проверка из настроек: звонит с нарастанием и сама останавливается. Повторный вызов — остановка. */
    fun test(ctx: Context) {
        if (playing) {
            stop()
            return
        }
        start(ctx, true)
        val ms = if (Prefs.rampRing(ctx)) Prefs.rampSeconds(ctx).coerceIn(3, 60) * 1000L + 4000L else 6000L
        handler.postDelayed(autoStop, ms)
    }

    private val autoStop = Runnable { stop() }

    fun stop() {
        handler.removeCallbacks(stepper)
        handler.removeCallbacks(autoStop)
        playing = false
        val p = player
        player = null
        if (p != null) {
            try {
                p.stop()
            } catch (_: Exception) {
            }
            try {
                p.release()
            } catch (_: Exception) {
            }
        }
        val r = fallback
        fallback = null
        try {
            r?.stop()
        } catch (_: Exception) {
        }
        stopVibration()
    }

    private var vib: Vibrator? = null

    private fun vibrate(app: Context) {
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                app.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                app.getSystemService(Vibrator::class.java)
            }
            if (v == null || !v.hasVibrator()) return
            vib = v
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 900), 0))
        } catch (_: Exception) {
        }
    }

    private fun stopVibration() {
        try {
            vib?.cancel()
        } catch (_: Exception) {
        }
        vib = null
    }
}
