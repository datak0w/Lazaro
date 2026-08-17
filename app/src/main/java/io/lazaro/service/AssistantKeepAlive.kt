package io.lazaro.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Mantiene Lázaro vivo para usuario ciego: si el sistema mata el FGS,
 * un latido o reinicio inmediato vuelve a arrancarlo (mientras el usuario
 * no lo haya parado a propósito).
 */
object AssistantKeepAlive {

    @Volatile
    var serviceAlive: Boolean = false
        private set

    fun markAlive(alive: Boolean) {
        serviceAlive = alive
    }

    fun scheduleHeartbeat(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = heartbeatPending(context)
        val triggerAt = SystemClock.elapsedRealtime() + HEARTBEAT_INTERVAL_MS
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                @Suppress("DEPRECATION")
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Sin permiso de alarma exacta; latido inexacto", e)
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo programar latido: ${e.message}", e)
        }
    }

    fun scheduleRestartSoon(context: Context, delayMs: Long = RESTART_DELAY_MS) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = restartPending(context)
        val triggerAt = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(500L)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                @Suppress("DEPRECATION")
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
            Log.i(TAG, "Reinicio programado en ${delayMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo programar reinicio: ${e.message}", e)
            // Último recurso: arrancar ya
            tryStartAssistant(context)
        }
    }

    fun cancelAll(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        try {
            am.cancel(heartbeatPending(context))
            am.cancel(restartPending(context))
        } catch (_: Exception) {
        }
    }

    fun tryStartAssistant(context: Context) {
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AssistantForegroundService::class.java),
            )
            Log.i(TAG, "Arrancando AssistantForegroundService (keep-alive)")
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo arrancar FGS: ${e.message}", e)
        }
    }

    private fun heartbeatPending(context: Context): PendingIntent {
        val intent = Intent(context, AssistantKeepAliveReceiver::class.java).apply {
            action = ACTION_HEARTBEAT
        }
        return PendingIntent.getBroadcast(
            context,
            REQ_HEARTBEAT,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun restartPending(context: Context): PendingIntent {
        val intent = Intent(context, AssistantKeepAliveReceiver::class.java).apply {
            action = ACTION_RESTART
        }
        return PendingIntent.getBroadcast(
            context,
            REQ_RESTART,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    const val ACTION_HEARTBEAT = "io.lazaro.action.KEEPALIVE_HEARTBEAT"
    const val ACTION_RESTART = "io.lazaro.action.KEEPALIVE_RESTART"

    private const val TAG = "LazaroKeepAlive"
    private const val HEARTBEAT_INTERVAL_MS = 4 * 60 * 1000L
    private const val RESTART_DELAY_MS = 2_000L
    private const val REQ_HEARTBEAT = 7101
    private const val REQ_RESTART = 7102
}
