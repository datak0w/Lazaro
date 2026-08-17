package io.lazaro.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import io.lazaro.alarm.AlarmAction
import io.lazaro.assistant.AssistantPrefsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Tras reinicio del móvil, arranca el FGS si el usuario había dejado el asistente activo
 * y reprograma las alarmas de Lázaro.
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject lateinit var assistantPrefs: AssistantPrefsRepository
    @Inject lateinit var alarmAction: AlarmAction

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                try {
                    alarmAction.rescheduleAfterBoot()
                } catch (e: Exception) {
                    Log.e(TAG, "BOOT: no se pudieron reprogramar alarmas: ${e.message}", e)
                }
                if (!assistantPrefs.wantsAssistantRunningNow()) {
                    Log.i(TAG, "BOOT: asistente no estaba activo; no arranco FGS")
                    return@launch
                }
                Log.i(TAG, "BOOT: arrancando AssistantForegroundService")
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AssistantForegroundService::class.java),
                )
                AssistantKeepAlive.scheduleHeartbeat(context)
            } catch (e: Exception) {
                Log.e(TAG, "BOOT: no se pudo arrancar FGS: ${e.message}", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "LazaroBoot"
    }
}
