@file:Suppress("DEPRECATION")

package com.example.voiceanswer

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Короткая вибрация в начале и в конце разговора. */
object Haptics {

    fun callStarted(ctx: Context) = buzz(ctx, longArrayOf(0, 140))

    fun callEnded(ctx: Context) = buzz(ctx, longArrayOf(0, 90, 110, 90))

    private fun buzz(ctx: Context, pattern: LongArray) {
        if (!Prefs.vibrateCall(ctx)) return
        try {
            val app = ctx.applicationContext
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                app.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                app.getSystemService(Vibrator::class.java)
            }
            if (v == null || !v.hasVibrator()) return
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {
        }
    }
}
