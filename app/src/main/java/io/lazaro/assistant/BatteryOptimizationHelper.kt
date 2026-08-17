package io.lazaro.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BatteryOptimizationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val assistantPrefs: AssistantPrefsRepository,
) {
    fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Una sola vez: habla (vía callback) y abre el diálogo del sistema.
     */
    suspend fun speakAndRequestOnce(speak: suspend (String) -> Unit) {
        if (isIgnoringBatteryOptimizations()) return
        if (assistantPrefs.wasBatteryOptPrompted()) return
        assistantPrefs.markBatteryOptPrompted()
        speak("Activa que Lazaro ignore la optimización de batería.")
        launchRequest()
    }

    private fun launchRequest() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo abrir diálogo de batería: ${e.message}")
            try {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback batería falló: ${e2.message}")
            }
        }
    }

    companion object {
        private const val TAG = "LazaroBattery"
    }
}
