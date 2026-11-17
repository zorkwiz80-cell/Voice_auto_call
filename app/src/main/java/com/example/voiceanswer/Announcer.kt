package com.example.voiceanswer

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Озвучивает, кто звонит. Всё приходит и уходит в главном потоке. */
class Announcer(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var pendingText: String? = null
    private var done: (() -> Unit)? = null

    init {
        try {
            tts = TextToSpeech(ctx.applicationContext) { status ->
                main.post { onInit(status) }
            }
        } catch (e: Exception) {
            failed = true
        }
    }

    private fun onInit(status: Int) {
        val t = tts
        if (status != TextToSpeech.SUCCESS || t == null) {
            failed = true
            finish()
            return
        }
        try {
            t.setLanguage(Locale.forLanguageTag("ru-RU"))
            t.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    main.post { finish() }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    main.post { finish() }
                }
            })
        } catch (e: Exception) {
            failed = true
            finish()
            return
        }
        ready = true
        val text = pendingText
        pendingText = null
        if (text != null) doSpeak(text)
    }

    /** Произносит текст и вызывает [onDone], когда закончил (или если озвучка недоступна). */
    fun speak(text: String, onDone: () -> Unit) {
        done = onDone
        when {
            failed -> finish()
            ready -> doSpeak(text)
            else -> pendingText = text
        }
    }

    private fun doSpeak(text: String) {
        val t = tts
        if (t == null) {
            finish()
            return
        }
        val r = try {
            t.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "caller")
        } catch (e: Exception) {
            TextToSpeech.ERROR
        }
        if (r == TextToSpeech.ERROR) finish()
    }

    private fun finish() {
        val d = done
        done = null
        d?.invoke()
    }

    fun stop() {
        pendingText = null
        done = null
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }

    fun shutdown() {
        stop()
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
    }
}
