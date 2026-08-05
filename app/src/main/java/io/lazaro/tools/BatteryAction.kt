package io.lazaro.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.lazaro.actions.ActionResult
import io.lazaro.cane.ble.CaneBleManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BatteryAction @Inject constructor(
    @ApplicationContext private val context: Context,
    private val batteryIntentDetector: BatteryIntentDetector,
    private val caneBleManager: CaneBleManager,
) {
    fun tryPrepare(userText: String): ActionResult? {
        if (!batteryIntentDetector.detect(userText)) return null
        return ActionResult.Success(formatBatteryStatus())
    }

    fun formatBatteryStatus(): String {
        val canePct = sanitizePercent(caneBleManager.state.value.batteryPercent)
        val phonePct = phoneBatteryPercent()
        val caneConnected = caneBleManager.state.value.isConnected

        val canePart = when {
            canePct != null -> "El bastón tiene un ${speakPercent(canePct)} por ciento."
            caneConnected -> "El bastón está conectado, pero aún no tengo su nivel de batería."
            else -> "No tengo el bastón conectado."
        }
        val phonePart = if (phonePct != null) {
            "El móvil tiene un ${speakPercent(phonePct)} por ciento."
        } else {
            "No pude leer la batería del móvil."
        }
        return "$canePart $phonePart"
    }

    fun phoneBatteryPercent(): Int? {
        return try {
            val intent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ) ?: return null
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return null
            sanitizePercent((level * 100) / scale)
        } catch (_: Exception) {
            null
        }
    }

    fun lowCaneWarningPhrase(percent: Int): String {
        return "Aviso: la batería del bastón está al ${speakPercent(percent)} por ciento. Conviene cargarlo."
    }

    companion object {
        fun sanitizePercent(value: Int?): Int? {
            if (value == null) return null
            if (value !in 0..100) return null
            return value
        }

        /** Números claros para TTS (evita «por ciento por ciento» y lecturas raras). */
        fun speakPercent(pct: Int): String {
            return when (pct) {
                0 -> "cero"
                100 -> "cien"
                else -> pct.toString()
            }
        }
    }
}
