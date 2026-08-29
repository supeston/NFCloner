package com.mifare.cloner.feedback

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

enum class FeedbackType {
    READ_SUCCESS,
    WRITE_SUCCESS,
    ERROR
}

class FeedbackManager(private val context: Context) {

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator ?: (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (_: Exception) {
        null
    }

    fun triggerFeedback(type: FeedbackType) {
        val timings = when (type) {
            FeedbackType.READ_SUCCESS -> {
                // 1 короткий четкий виброотклик (~80 мс)
                longArrayOf(0, 80)
            }
            FeedbackType.WRITE_SUCCESS -> {
                // 2 плотных четких импульса (вибрация 90мс / пауза 70мс / вибрация 90мс)
                longArrayOf(0, 90, 70, 90)
            }
            FeedbackType.ERROR -> {
                // Тройной дребезг (3 быстрых импульса)
                longArrayOf(0, 45, 40, 45, 40, 45)
            }
        }

        vibratePattern(timings)
    }

    private fun vibratePattern(timings: LongArray) {
        val vib = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(timings, -1)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val attrs = VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_TOUCH)
                        .build()
                    vib.vibrate(effect, attrs)
                } else {
                    val audioAttrs = AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .build()
                    vib.vibrate(effect, audioAttrs)
                }
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(timings, -1)
            }
        } catch (_: Exception) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createWaveform(timings, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(timings, -1)
                }
            } catch (_: Exception) {
                try {
                    @Suppress("DEPRECATION")
                    vib.vibrate(timings.getOrElse(1) { 70L })
                } catch (_: Exception) {}
            }
        }
    }

    fun release() {
        try {
            vibrator?.cancel()
        } catch (_: Exception) {}
    }
}
