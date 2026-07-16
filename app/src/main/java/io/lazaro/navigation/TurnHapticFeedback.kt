package io.lazaro.navigation

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Háptica pensada para persona ciega: patrones cortos, claros y distintivos.
 * Sin depender de mirar la pantalla.
 */
object TurnHapticFeedback {

    fun pulseForInstruction(context: Context, instruction: String) {
        if (!MapsNavigationParser.isTurnInstruction(instruction)) return
        val side = MapsNavigationParser.turnSide(instruction) ?: return
        pulseTurn(context, side)
    }

    /** Giro Maps / IMU: izquierda=2 pulsos, derecha=1 largo, retorno=3. */
    fun pulseTurn(context: Context, side: TurnSide) {
        val vibrator = vibrator(context) ?: return
        val effect = when (side) {
            TurnSide.LEFT -> waveform(
                longArrayOf(0, 90, 70, 90),
                intArrayOf(0, 220, 0, 220),
            )
            TurnSide.RIGHT -> waveform(
                longArrayOf(0, 160),
                intArrayOf(0, 250),
            )
            TurnSide.U_TURN -> waveform(
                longArrayOf(0, 100, 55, 100, 55, 100),
                intArrayOf(0, 230, 0, 230, 0, 230),
            )
        }
        vibrator.vibrate(effect)
    }

    /** Al alcanzar el ángulo correcto (éxito). */
    fun pulseAligned(context: Context) {
        val vibrator = vibrator(context) ?: return
        vibrator.vibrate(
            waveform(
                longArrayOf(0, 45, 35, 90),
                intArrayOf(0, 180, 0, 255),
            ),
        )
    }

    /** Obstáculo delante: alerta fuerte y breve. */
    fun pulseObstacle(context: Context) {
        val vibrator = vibrator(context) ?: return
        vibrator.vibrate(
            waveform(
                longArrayOf(0, 120, 40, 120),
                intArrayOf(0, 255, 0, 255),
            ),
        )
    }

    /** Micro-pulso al lado hacia el que guiar (refuerzo de pitido). */
    fun pulseGuideNudge(context: Context, turnLeft: Boolean) {
        val vibrator = vibrator(context) ?: return
        val effect = if (turnLeft) {
            waveform(longArrayOf(0, 50, 40, 50), intArrayOf(0, 160, 0, 160))
        } else {
            waveform(longArrayOf(0, 85), intArrayOf(0, 190))
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

    private fun waveform(timings: LongArray, amplitudes: IntArray): VibrationEffect {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            VibrationEffect.createWaveform(timings, amplitudes, -1)
        } else {
            @Suppress("DEPRECATION")
            VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }
}
