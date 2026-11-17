package com.example.voiceanswer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Один раз слушает фразу вроде «позвони маме» и возвращает варианты распознавания. */
class VoiceDial(
    context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (List<String>) -> Unit,
    private val onError: (String) -> Unit
) {
    private val ctx = context.applicationContext
    private var sr: SpeechRecognizer? = null
    private var done = false
    private var retried = false
    private var offline = true

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            onError("Нет службы распознавания речи — установите приложение «Google»")
            return
        }
        done = false
        listen()
    }

    private fun listen() {
        try {
            sr?.destroy()
            val r = SpeechRecognizer.createSpeechRecognizer(ctx)
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onPartialResults(partialResults: Bundle?) {
                    val l = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!done && !l.isNullOrEmpty()) onPartial(l[0])
                }

                override fun onResults(results: Bundle?) {
                    if (done) return
                    val l = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    done = true
                    if (l.isNullOrEmpty()) onError("Не расслышал. Попробуйте ещё раз") else onFinal(l)
                }

                override fun onError(error: Int) {
                    if (done) return
                    if ((error == 12 || error == 13) && offline) {
                        offline = false
                        listen()
                        return
                    }
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        done = true
                        onError("Не расслышал. Попробуйте ещё раз")
                    } else if (!retried) {
                        retried = true
                        listen()
                    } else {
                        done = true
                        onError("Распознавание речи не отвечает (код $error). Проверьте интернет")
                    }
                }
            })
            sr = r
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 8)
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            if (offline) i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            r.startListening(i)
        } catch (e: Exception) {
            done = true
            onError("Не удалось запустить распознавание")
        }
    }

    fun stop() {
        done = true
        try {
            sr?.cancel()
            sr?.destroy()
        } catch (_: Exception) {
        }
        sr = null
    }
}
