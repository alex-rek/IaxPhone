package com.glaude.iaxphone

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Отклик клавиатуры: короткий тон клавиши и вибрация — по настройкам «Аудио». */
object KeyFeedback {
    private var dialTone: ToneGenerator? = null
    private var callTone: ToneGenerator? = null

    /** [inCall] — нажатие во время разговора (DTMF): тон идёт в разговорный поток. */
    fun onKey(ctx: Context, digit: Char, inCall: Boolean) {
        if (AudioPrefs.keyVibrate) vibrate(ctx)
        if (AudioPrefs.keySound) tone(digit, inCall)
    }

    private fun tone(digit: Char, inCall: Boolean) {
        val tone = when (digit) {
            in '0'..'9' -> ToneGenerator.TONE_DTMF_0 + (digit - '0')
            '*' -> ToneGenerator.TONE_DTMF_S
            '#' -> ToneGenerator.TONE_DTMF_P
            else -> return
        }
        try {
            val gen = if (inCall) {
                callTone ?: ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60).also { callTone = it }
            } else {
                dialTone ?: ToneGenerator(AudioManager.STREAM_DTMF, 70).also { dialTone = it }
            }
            gen.startTone(tone, 120)
        } catch (_: RuntimeException) {
            // ToneGenerator может не создаться, если аудиоресурсы заняты — отклик просто пропускаем.
        }
    }

    private fun vibrate(ctx: Context) {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (v == null || !v.hasVibrator()) return
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        } else {
            VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        try { v.vibrate(effect) } catch (_: Exception) { }
    }
}
