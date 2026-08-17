package io.lazaro.messaging

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Build
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@AndroidEntryPoint
class LazaroNotificationListenerService : NotificationListenerService() {

    @Inject lateinit var messageRepository: MessageRepository
    @Inject lateinit var navigationGuidanceMonitor: NavigationGuidanceMonitor

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ingestMutex = Mutex()

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
        scope.launch { ingestSbn(sbn) }
    }

    /**
     * Relee la barra de notificaciones (p. ej. al pedir «lee mensajes»).
     * Necesario si el listener se conectó tarde o WhatsApp ya tenía chats abiertos.
     */
    suspend fun ingestAllActiveMessaging(): Int {
        return ingestMutex.withLock {
            val list = activeNotifications ?: return@withLock 0
            var added = 0
            for (sbn in list) {
                if (sbn.packageName !in MessageApps.SUPPORTED) continue
                added += ingestSbn(sbn)
            }
            added
        }
    }

    private suspend fun ingestSbn(sbn: StatusBarNotification): Int {
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return 0
        val pkg = sbn.packageName
        if (pkg !in MessageApps.SUPPORTED) return 0

        val parsed = parseMessages(sbn)
        var added = 0
        for (msg in parsed) {
            if (messageRepository.addMessage(msg)) added++
        }
        return added
    }

    private fun parseMessages(sbn: StatusBarNotification): List<IncomingMessage> {
        val pkg = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            .ifBlank { extras.getCharSequence("android.title")?.toString().orEmpty() }
            .ifBlank { "Desconocido" }

        val fromStyle = extractMessagingStyle(sbn.notification)
        if (fromStyle.isNotEmpty()) {
            return fromStyle.mapNotNull { (sender, text, time) ->
                if (MessageRepository.isNoiseNotification(sender.ifBlank { title }, text)) {
                    null
                } else {
                    IncomingMessage(
                        packageName = pkg,
                        appLabel = MessageApps.labelFor(pkg),
                        sender = sender.ifBlank { title },
                        text = text,
                        timestamp = if (time > 0L) time else sbn.postTime,
                    )
                }
            }
        }

        val text = extractMessageText(extras)
        if (text.isBlank()) return emptyList()
        if (MessageRepository.isNoiseNotification(title, text)) return emptyList()
        return listOf(
            IncomingMessage(
                packageName = pkg,
                appLabel = MessageApps.labelFor(pkg),
                sender = title,
                text = text,
                timestamp = sbn.postTime,
            ),
        )
    }

    private fun extractMessagingStyle(notification: Notification): List<Triple<String, String, Long>> {
        return extractMessagesExtraFallback(notification.extras)
    }

    @Suppress("DEPRECATION")
    private fun extractMessagesExtraFallback(extras: Bundle): List<Triple<String, String, Long>> {
        val arr = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            ?: extras.getParcelableArray("android.messages")
            ?: return emptyList()
        val out = ArrayList<Triple<String, String, Long>>(arr.size)
        for (item in arr) {
            val b = item as? Bundle ?: continue
            val text = sequenceOf("text", "android.text")
                .mapNotNull { key -> b.getCharSequence(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() } }
                .firstOrNull()
                .orEmpty()
            if (text.isBlank()) continue
            val sender = sequenceOf("sender", "android.sender")
                .mapNotNull { key -> b.getCharSequence(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() } }
                .firstOrNull()
                .orEmpty()
            val time = when {
                b.containsKey("time") -> b.getLong("time", 0L)
                b.containsKey("android.time") -> b.getLong("android.time", 0L)
                else -> 0L
            }
            out.add(Triple(sender, text, time))
        }
        return out
    }

    private fun extractMessageText(extras: Bundle): String {
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            .ifBlank { extras.getCharSequence("android.text")?.toString()?.trim().orEmpty() }
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.trim()?.takeIf { line -> line.isNotEmpty() } }
            .orEmpty()

        val candidate = when {
            lines.isNotEmpty() -> lines.last()
            bigText.isNotBlank() -> bigText
            else -> text
        }
        return candidate.trim()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        scope.launch {
            ingestAllActiveMessaging()
            messageRepository.refreshUnreadCount()
        }
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
            val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)
                ?.toString().orEmpty()
            if (!namesMatch(title, senderName)) continue
            if (tryDirectReply(sbn, message)) return true
        }
        return false
    }

    /** Solo WhatsApp / WhatsApp Business — nunca SMS ni Telegram. */
    fun replyToWhatsAppOnly(senderName: String, message: String): Boolean {
        val notifications = activeNotifications ?: return false
        val waPackages = setOf(MessageApps.WHATSAPP, MessageApps.WHATSAPP_BUSINESS)
        // Preferir coincidencia exacta de título, luego contains.
        val candidates = notifications.filter { it.packageName in waPackages }
        for (sbn in candidates) {
            val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)
                ?.toString().orEmpty()
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

        suspend fun syncActiveMessages(): Int {
            return instance?.ingestAllActiveMessaging() ?: 0
        }

        fun replyToActiveNotification(
            senderName: String,
            message: String,
            packageName: String,
        ): Boolean {
            // Blindaje: nunca usar SMS/Telegram aunque el caller pase ese package.
            if (packageName == MessageApps.SMS || packageName == MessageApps.TELEGRAM) {
                return replyToWhatsAppNotification(senderName, message)
            }
            return instance?.replyToNotification(senderName, message, packageName) ?: false
        }

        fun replyToWhatsAppNotification(senderName: String, message: String): Boolean {
            return instance?.replyToWhatsAppOnly(senderName, message) ?: false
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
            val t = normalizeName(notificationTitle)
            val q = normalizeName(query)
            if (t.isBlank() || q.isBlank()) return false
            if (t == q) return true
            if (t.contains(q) || q.contains(t)) return true
            // Evitar cruces flojos tipo «a»/«de» que mezclaban SMS con WhatsApp
            val tTokens = t.split(" ").filter { it.length >= 3 }
            val qTokens = q.split(" ").filter { it.length >= 3 }
            if (tTokens.isEmpty() || qTokens.isEmpty()) return false
            return tTokens.any { tt -> qTokens.any { qt -> tt == qt || tt.startsWith(qt) || qt.startsWith(tt) } }
        }

        private fun normalizeName(value: String): String {
            return java.text.Normalizer.normalize(value.lowercase().trim(), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }
}
