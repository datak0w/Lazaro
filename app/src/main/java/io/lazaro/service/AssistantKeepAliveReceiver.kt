package io.lazaro.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import io.lazaro.assistant.AssistantPrefsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Latido / reinicio tras caída del FGS, y también tras actualizar la app.
 */
@AndroidEntryPoint
class AssistantKeepAliveReceiver : BroadcastReceiver() {

    @Inject lateinit var assistantPrefs: AssistantPrefsRepository

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                when (action) {
                    AssistantKeepAlive.ACTION_HEARTBEAT,
                    AssistantKeepAlive.ACTION_RESTART,
                    Intent.ACTION_MY_PACKAGE_REPLACED,
                    -> ensureRunning(context, action)
                    else -> Unit
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun ensureRunning(context: Context, reason: String) {
        if (!assistantPrefs.wantsAssistantRunningNow()) {
            Log.i(TAG, "$reason: usuario paró Lázaro; no reinicio")
            AssistantKeepAlive.cancelAll(context)
            return
        }
        if (AssistantKeepAlive.serviceAlive && reason == AssistantKeepAlive.ACTION_HEARTBEAT) {
            // Sigue vivo: reprogramar siguiente latido
            AssistantKeepAlive.scheduleHeartbeat(context)
            return
        }
        Log.i(TAG, "$reason: arrancando (alive=${AssistantKeepAlive.serviceAlive})")
        AssistantKeepAlive.tryStartAssistant(context)
        AssistantKeepAlive.scheduleHeartbeat(context)
    }

    companion object {
        private const val TAG = "LazaroKeepAliveRx"
    }
}
