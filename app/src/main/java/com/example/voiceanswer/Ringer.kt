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
            val p = player ?: return
            val g = currentGain()
            try {
                p.setVolume(g, g)
            } catch (_: Exception) {
            }
            if (SystemClock.elapsedRealtime() - startedAt < rampMs) handler.postDelayed(this, 150)
        }
    }

    private fun currentGain(): Float {
        if (rampMs <= 0L) return maxGain
        val t = min(1f, (SystemClock.elapsedRealtime() - startedAt).toFloat() / rampMs)
        return (0.06f + 0.94f * t * t) * maxGain
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

    fun start(ctx: Context, known: Boolean) {
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

        maxGain = if (Prefs.quietRinger(app)) 0.5f else 1f
        rampMs = if (Prefs.rampRing(app)) Prefs.rampSeconds(app).coerceIn(3, 60) * 1000L else 0L
        startedAt = SystemClock.elapsedRealtime()

        val uri: Uri? = try {
            RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        } catch (e: Exception) {
            null
        }
        if (uri == null) return

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(attrs)
            mp.setDataSource(app, uri)
            mp.isLooping = true
            mp.prepare()
            val g = currentGain()
            mp.setVolume(g, g)
            mp.start()
            player = mp
            playing = true
            handler.removeCallbacks(stepper)
            if (rampMs > 0L) handler.postDelayed(stepper, 150)
        } catch (e: Exception) {
            // Запасной путь: обычный рингтон без нарастания
            try {
                player?.release()
            } catch (_: Exception) {
            }
            player = null
            try {
                val r = RingtoneManager.getRingtone(app, uri)
                if (r != null) {
                    r.audioAttributes = attrs
                    if (Build.VERSION.SDK_INT >= 28) r.isLooping = true
                    r.play()
                    fallback = r
                    playing = true
                }
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        handler.removeCallbacks(stepper)
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
