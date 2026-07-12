package io.lazaro.navigation

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object TurnHapticFeedback {

    fun pulseForInstruction(context: Context, instruction: String) {
        if (!MapsNavigationParser.isTurnInstruction(instruction)) return
        val side = MapsNavigationParser.turnSide(instruction) ?: return
        pulse(context, side)
    }

    fun pulse(context: Context, side: TurnSide) {
        val vibrator = vibrator(context) ?: return
        val effect = when (side) {
            TurnSide.LEFT -> waveformEffect(longArrayOf(0, 70, 60, 70), intArrayOf(0, 160, 0, 160))
            TurnSide.RIGHT -> waveformEffect(longArrayOf(0, 110), intArrayOf(0, 200))
            TurnSide.U_TURN -> waveformEffect(longArrayOf(0, 90, 50, 90, 50, 90), intArrayOf(0, 180, 0, 180, 0, 180))
        }
        vibrator.vibrate(effect)
    }

    private fun vibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun waveformEffect(timings: LongArray, amplitudes: IntArray): VibrationEffect {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            VibrationEffect.createWaveform(timings, amplitudes, -1)
        } else {
            @Suppress("DEPRECATION")
            VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }
}
