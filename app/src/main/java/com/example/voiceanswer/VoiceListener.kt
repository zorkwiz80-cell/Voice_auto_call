@file:Suppress("DEPRECATION")

package com.example.voiceanswer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Слушает микрофон и ищет слова-команды из настроек.
 * [oneShot] = true: после первой найденной команды перестаёт слушать (так при звонке).
 * Один и тот же распознаватель переиспользуется (без пауз на пересоздание), а «писк»
 * включения микрофона заглушается, пока идёт прослушивание.
 */
class VoiceListener(
    context: Context,
    private val oneShot: Boolean,
    private val onCommand: (Logic.Command) -> Unit,
    private val onHeard: (String) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onStatus: (String) -> Unit,
    /** Если задан — слушаем фразы целиком (режим «слово-активатор»): true = фраза обработана. */
    private val onPhrase: ((List<String>) -> Boolean)? = null,
    /** Глушить «писк» микрофона только когда не играет музыка (для постоянного прослушивания). */
    private val smartMute: Boolean = false,
    /** Подсказка распознавателю: слова, которые вероятнее всего прозвучат (имена из контактов и т.п.). */
    private val bias: (() -> List<String>)? = null
) {
    private val ctx: Context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var accept: List<String> = emptyList()
    private var reject: List<String> = emptyList()
    private var errors = 0
    private var preferOffline = true
    private var scoStarted = false
    private val mutedByUs = HashSet<Int>()
    private var fired = false
    private var lastHeard = ""
    private var lastActivity = 0L

    /** Сторож: если распознаватель завис и молчит дольше 30 секунд — пересоздаём его. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            if (SystemClock.elapsedRealtime() - lastActivity > 30000) {
                errors = 0
                begin(true)
            }
            handler.postDelayed(this, 10000)
        }
    }

    var running = false
        private set

    companion object {
        // только музыка: на многих телефонах каналы звонка и уведомлений связаны, и их глушение отключало рингтон
        private val BEEP_STREAMS = intArrayOf(AudioManager.STREAM_MUSIC)

        private fun unmuteAll(ctx: Context, streams: Set<Int>) {
            val am = ctx.getSystemService(AudioManager::class.java)
            for (st in streams) {
                try {
                    am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0)
                } catch (_: Exception) {
                }
            }
        }

        /** Версии 3.6–3.16 могли оставить звонок и уведомления заглушёнными — один раз возвращаем звук. */
        private fun fixOldMute(ctx: Context) {
            try {
                val sp = ctx.getSharedPreferences("voice_answer", Context.MODE_PRIVATE)
                if (sp.getBoolean("ring_fix_done", false)) return
                sp.edit().putBoolean("ring_fix_done", true).apply()
                val am = ctx.getSystemService(AudioManager::class.java)
                if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
                for (st in intArrayOf(AudioManager.STREAM_RING, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)) {
                    try {
                        if (am.isStreamMute(st)) am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0)
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }

        /** Если приложение убили во время прослушивания, звук мог остаться выключенным — возвращаем. */
        fun restoreSound(ctx: Context) {
            fixOldMute(ctx)
            val left = Prefs.beepMuted(ctx)
            if (left.isEmpty()) return
            unmuteAll(ctx.applicationContext, left)
            Prefs.setBeepMuted(ctx, emptySet())
        }
    }

    fun start() {
        if (running) return
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onStatus("Нет разрешения на микрофон")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            onStatus("Нет службы распознавания речи — установите приложение «Google»")
            return
        }
        accept = Prefs.acceptWords(ctx)
        reject = Prefs.rejectWords(ctx)
        running = true
        fired = false
        errors = 0
        lastHeard = ""
        if (!smartMute) muteBeep(true)
        startHeadsetMic()
        begin(true)
        if (onPhrase != null) handler.postDelayed(watchdog, 10000)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        destroyRecognizer()
        stopHeadsetMic()
        muteBeep(false)
        onLevel(0f)
    }

    private var quick = 0
    private var lastBegin = 0L

    private fun begin(recreate: Boolean) {
        if (!running) return
        if (onPhrase != null) {
            // пока играет видео или музыка (TikTok и т.п.) микрофон не трогаем — иначе он мигает и мешает
            if (!Prefs.wakeDuringMedia(ctx)) {
                val playing = try {
                    ctx.getSystemService(AudioManager::class.java).isMusicActive
                } catch (e: Exception) {
                    false
                }
                if (playing) {
                    destroyRecognizer()
                    muteBeep(false)
                    handler.postDelayed({ begin(false) }, 3000)
                    return
                }
            }
            // частые перезапуски подряд (шум вокруг) — делаем паузы длиннее, чтобы микрофон не «дёргался»
            val now = SystemClock.elapsedRealtime()
            if (now - lastBegin < 1500) quick++ else quick = 0
            lastBegin = now
            if (quick >= 3) {
                val wait = minOf(quick * 500L, 4000L)
                quick++
                handler.postDelayed({ beginNow(recreate) }, wait)
                return
            }
        }
        beginNow(recreate)
    }

    private fun beginNow(recreate: Boolean) {
        if (!running) return
        if (recreate || recognizer == null) {
            destroyRecognizer()
            val created = try {
                SpeechRecognizer.createSpeechRecognizer(ctx)
            } catch (e: Exception) {
                onStatus("Не удалось запустить распознавание")
                return
            }
            created.setRecognitionListener(listener)
            recognizer = created
        }
        val sr = recognizer ?: return
        if (smartMute) {
            try {
                // пока играет музыка — её не глушим (иначе она будет прерываться), системные «пики» глушим всегда
                val am = ctx.getSystemService(AudioManager::class.java)
                val music = am.isMusicActive
                if (music && mutedByUs.remove(AudioManager.STREAM_MUSIC)) {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                }
                muteBeep(true, !music)
            } catch (_: Exception) {
            }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 10)
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        if (preferOffline) intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        if (Build.VERSION.SDK_INT >= 33) {
            // подсказываем распознавателю, какие слова ждём — «принять» реже превратится в «привет»
            intent.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(if (bias != null) bias.invoke() else accept + reject))
        }
        if (onPhrase != null) {
            // фраза «Абобус, позвони дяде Серёже» произносится с паузами — не обрывать её раньше времени
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        lastActivity = SystemClock.elapsedRealtime()
        try {
            sr.startListening(intent)
        } catch (e: Exception) {
            schedule(500, true)
        }
    }

    private fun destroyRecognizer() {
        val sr = recognizer ?: return
        recognizer = null
        try {
            sr.cancel()
            sr.destroy()
        } catch (_: Exception) {
        }
    }

    private fun schedule(delayMs: Long, recreate: Boolean = false) {
        if (!running) return
        handler.postDelayed({ begin(recreate) }, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            lastActivity = SystemClock.elapsedRealtime()
            onStatus("")
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            if (!running) return
            lastActivity = SystemClock.elapsedRealtime()
            val quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                onStatus("Нет разрешения на микрофон")
                if (onPhrase != null) schedule(5000, true)
                return
            }
            if (error == 12 || error == 13) preferOffline = false // нет офлайн-языка: идём через интернет
            if (quiet) {
                errors = 0
                schedule(0)
            } else {
                errors++
                if (errors >= 3) onStatus("Распознавание речи не отвечает (код $error). Проверьте интернет")
                // занят или сломался — пересоздаём
                schedule(minOf(300L * errors, 3000L), true)
            }
        }

        override fun onResults(results: Bundle?) {
            if (!running) return
            errors = 0
            // в режиме слова-активатора слушаем дальше всегда, даже после команды
            if (!check(results) || onPhrase != null) schedule(0)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!running) return
            if (onPhrase == null) check(partialResults)
        }
    }

    private fun check(bundle: Bundle?): Boolean {
        val list = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return false
        val top = list.firstOrNull()
        if (!top.isNullOrBlank() && top != lastHeard) {
            lastHeard = top
            onHeard(top)
        }
        if (onPhrase != null) {
            if (fired) return true
            if (onPhrase.invoke(list)) {
                fired = true
                handler.postDelayed({ fired = false }, 4000)
                return true
            }
            return false
        }
        if (fired) return true
        val cmd = Logic.match(list, accept, reject) ?: return false
        fired = true
        if (oneShot) {
            // не уничтожаем распознаватель внутри его же колбэка
            handler.post { stop() }
        } else {
            handler.postDelayed({ fired = false }, 1500)
        }
        onCommand(cmd)
        return true
    }

    // ---- заглушить «писк» микрофона ----

    private fun muteBeep(on: Boolean, includeMusic: Boolean = true) {
        try {
            val am = ctx.getSystemService(AudioManager::class.java)
            if (on) {
                // «писк» микрофона может идти по разным каналам; рингтон звонка (STREAM_RING) не трогаем
                for (st in BEEP_STREAMS) {
                    if (st == AudioManager.STREAM_MUSIC && !includeMusic) continue
                    try {
                        if (!am.isStreamMute(st) && !mutedByUs.contains(st)) {
                            am.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, 0)
                            mutedByUs.add(st)
                        }
                    } catch (_: Exception) {
                    }
                }
                Prefs.setBeepMuted(ctx, mutedByUs)
            } else if (mutedByUs.isNotEmpty()) {
                unmuteAll(ctx, mutedByUs)
                mutedByUs.clear()
                Prefs.setBeepMuted(ctx, emptySet())
            }
        } catch (_: Exception) {
        }
    }

    // ---- микрофон Bluetooth-наушников (по возможности) ----

    private fun startHeadsetMic() {
        if (!Prefs.headsetMic(ctx)) return
        if (Calls.headsetKind(ctx) != Calls.Headset.BLUETOOTH) return
        try {
            val am = ctx.getSystemService(AudioManager::class.java)
            if (am.isBluetoothScoAvailableOffCall) {
                am.startBluetoothSco()
                scoStarted = true
            }
        } catch (_: Exception) {
        }
    }

    private fun stopHeadsetMic() {
        if (!scoStarted) return
        scoStarted = false
        try {
            ctx.getSystemService(AudioManager::class.java).stopBluetoothSco()
        } catch (_: Exception) {
        }
    }
}
