package io.lazaro.messaging

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import io.lazaro.messaging.entity.IncomingMessage
import io.lazaro.messaging.entity.MessageApps
import io.lazaro.navigation.NavigationGuidanceMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class LazaroNotificationListenerService : NotificationListenerService() {

    @Inject lateinit var messageRepository: MessageRepository
    @Inject lateinit var navigationGuidanceMonitor: NavigationGuidanceMonitor

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName

        if (pkg == GOOGLE_MAPS_PACKAGE) {
            navigationGuidanceMonitor.onMapsNotification(sbn.notification.extras)
            return
        }

        if (pkg !in MessageApps.SUPPORTED) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        if (text.isBlank()) return

        if (title.isBlank() && text.contains("mensajes de")) return

        val sender = title.ifBlank { "Desconocido" }
        scope.launch {
            messageRepository.addMessage(
                IncomingMessage(
                    packageName = pkg,
                    appLabel = MessageApps.labelFor(pkg),
                    sender = sender,
                    text = text,
                    timestamp = sbn.postTime,
                ),
            )
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        scope.launch { messageRepository.refreshUnreadCount() }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == GOOGLE_MAPS_PACKAGE) {
            navigationGuidanceMonitor.onMapsNotificationRemoved()
        }
    }

    fun replyToNotification(senderName: String, message: String, packageName: String): Boolean {
        val notifications = activeNotifications ?: return false
        for (sbn in notifications) {
            if (sbn.packageName != packageName) continue
            val title = sbn.notification.extras.getCharSequence("android.title")?.toString().orEmpty()
            if (!namesMatch(title, senderName)) continue
            if (tryDirectReply(sbn, message)) return true
        }
        return false
    }

    private fun tryDirectReply(sbn: StatusBarNotification, message: String): Boolean {
        val actions = sbn.notification.actions ?: return false
        for (action in actions) {
            val remoteInputs = action.remoteInputs ?: continue
            if (remoteInputs.isEmpty()) continue

            return try {
                val intent = Intent()
                val bundle = Bundle()
                for (remoteInput in remoteInputs) {
                    bundle.putCharSequence(remoteInput.resultKey, message)
                }
                RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
                action.actionIntent.send(this, 0, intent)
                true
            } catch (_: PendingIntent.CanceledException) {
                false
            }
        }
        return false
    }

    fun tryCloseMapsNotification(sbn: StatusBarNotification): Boolean {
        val actions = sbn.notification.actions
        if (actions != null) {
            for (action in actions) {
                val title = action.title?.toString()?.lowercase().orEmpty()
                if (title.contains("salir") || title.contains("detener") ||
                    title.contains("stop") || title.contains("end") || title.contains("cerrar")
                ) {
                    return try {
                        action.actionIntent.send()
                        true
                    } catch (_: PendingIntent.CanceledException) {
                        false
                    }
                }
            }
        }
        return try {
            cancelNotification(sbn.key)
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"

        @Volatile
        private var instance: LazaroNotificationListenerService? = null

        fun replyToActiveNotification(
            senderName: String,
            message: String,
            packageName: String,
        ): Boolean {
            return instance?.replyToNotification(senderName, message, packageName) ?: false
        }

        fun closeMapsNavigation(): Boolean {
            val service = instance ?: return false
            val notifications = service.activeNotifications ?: return false
            var closed = false
            for (sbn in notifications) {
                if (sbn.packageName != GOOGLE_MAPS_PACKAGE) continue
                closed = service.tryCloseMapsNotification(sbn) || closed
            }
            return closed
        }

        private fun namesMatch(notificationTitle: String, query: String): Boolean {
            val t = notificationTitle.lowercase().trim()
            val q = query.lowercase().trim()
            if (t.isBlank() || q.isBlank()) return false
            return t.contains(q) || q.contains(t) ||
                t.split(" ").any { q.contains(it) && it.length > 2 }
        }
    }
}
