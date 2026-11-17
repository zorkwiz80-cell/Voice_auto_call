@file:Suppress("DEPRECATION")

package com.example.voiceanswer

import android.content.Context
import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService

/**
 * Система привязывает эту службу только пока есть звонок — поэтому в фоне приложение
 * ничего не делает и никаких постоянных уведомлений не показывает.
 */
class CallService : InCallService() {

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Calls.onAdded(this, call)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Calls.onRemoved(this, call)
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        Calls.onAudioState(audioState)
    }

    override fun onDestroy() {
        Calls.onServiceDestroyed()
        super.onDestroy()
    }
}

/** Кнопки «Принять / Отклонить / Завершить» в уведомлении о звонке. */
class CallActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_ANSWER -> Calls.answer(context, false)
            ACTION_DECLINE -> Calls.reject()
            ACTION_HANGUP -> Calls.hangup()
        }
    }

    companion object {
        const val ACTION_ANSWER = "com.example.voiceanswer.ANSWER"
        const val ACTION_DECLINE = "com.example.voiceanswer.DECLINE"
        const val ACTION_HANGUP = "com.example.voiceanswer.HANGUP"
    }
}
