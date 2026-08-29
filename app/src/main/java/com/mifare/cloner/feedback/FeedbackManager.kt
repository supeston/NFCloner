package com.mifare.cloner.feedback

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class FeedbackType {
    READ_SUCCESS,
    WRITE_SUCCESS,
    ERROR
}

class FeedbackManager(private val context: Context) {

    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private var toneGenerator: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
    } catch (_: Exception) {
        null
    }

    fun triggerFeedback(type: FeedbackType) {
        when (type) {
            FeedbackType.READ_SUCCESS -> {
                // 1 short vibration pulse (~70 ms) + confirming short beep
                vibrateReadSuccess()
                playTone(ToneGenerator.TONE_PROP_BEEP, 80)
            }
            FeedbackType.WRITE_SUCCESS -> {
                // 2 dense distinct pulses: vibration 90ms / pause 70ms / vibration 90ms + double success tone
                vibrateWriteSuccess()
                playDoubleSuccessTone()
            }
            FeedbackType.ERROR -> {
                // Triple jitter: 3 quick pulses (45ms on / 40ms off / 45ms on / 40ms off / 45ms on) + low error tone
                vibrateError()
                playTone(ToneGenerator.TONE_PROP_NACK, 250)
            }
        }
    }

    private fun vibrateReadSuccess() {
        if (vibrator == null || !vibrator.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(70)
            }
        } catch (_: Exception) {}
    }

    private fun vibrateWriteSuccess() {
        if (vibrator == null || !vibrator.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 90ms on / 70ms off / 90ms on
                val timings = longArrayOf(0, 90, 70, 90)
                val amplitudes = intArrayOf(0, 255, 0, 255)
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                val timings = longArrayOf(0, 90, 70, 90)
                vibrator.vibrate(timings, -1)
            }
        } catch (_: Exception) {}
    }

    private fun vibrateError() {
        if (vibrator == null || !vibrator.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 3 quick pulses: 45ms on, 40ms off, 45ms on, 40ms off, 45ms on
                val timings = longArrayOf(0, 45, 40, 45, 40, 45)
                val amplitudes = intArrayOf(0, 240, 0, 240, 0, 240)
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                val timings = longArrayOf(0, 45, 40, 45, 40, 45)
                vibrator.vibrate(timings, -1)
            }
        } catch (_: Exception) {}
    }

    private fun playTone(toneType: Int, durationMs: Int) {
        try {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
            }
            toneGenerator?.startTone(toneType, durationMs)
        } catch (_: Exception) {}
    }

    private fun playDoubleSuccessTone() {
        coroutineScope.launch {
            try {
                playTone(ToneGenerator.TONE_PROP_ACK, 70)
                delay(90)
                playTone(ToneGenerator.TONE_PROP_BEEP2, 100)
            } catch (_: Exception) {}
        }
    }

    fun release() {
        try {
            toneGenerator?.release()
            toneGenerator = null
        } catch (_: Exception) {}
    }
}
