package io.lazaro.integration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.lazaro.service.AssistantForegroundService

/**
 * Recibe comandos externos (p. ej. WeHack / bastón WeWALK) para controlar el asistente.
 *
 * Acciones documentadas:
 * - [ACTION_START_ASSISTANT] — inicia el servicio en primer plano
 * - [ACTION_STOP_ASSISTANT] — detiene el asistente
 * - [ACTION_START_LISTENING] — interrumpe TTS y empieza a escuchar (grabar comando)
 */
class CaneBridgeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            ACTION_START_ASSISTANT, ACTION_START_LISTENING -> {
                val serviceIntent = Intent(context, AssistantForegroundService::class.java).apply {
                    this.action = action
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }
            ACTION_STOP_ASSISTANT -> {
                context.startService(
                    Intent(context, AssistantForegroundService::class.java).apply {
                        this.action = AssistantForegroundService.ACTION_STOP
                    }
                )
            }
        }
    }

    companion object {
        const val ACTION_START_ASSISTANT = "io.lazaro.action.START_ASSISTANT"
        const val ACTION_STOP_ASSISTANT = "io.lazaro.action.STOP_ASSISTANT"
        const val ACTION_START_LISTENING = "io.lazaro.action.START_LISTENING"
    }
}
