package io.lazaro.messaging

import io.lazaro.messaging.dao.MessageDao
import io.lazaro.messaging.entity.IncomingMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepository @Inject constructor(
    private val messageDao: MessageDao,
    private val replyContext: ReplyContext,
) {
    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    /** @return true si se insertó un mensaje nuevo. */
    suspend fun addMessage(message: IncomingMessage): Boolean {
        if (isNoiseNotification(message.sender, message.text)) return false

        val sinceMs = System.currentTimeMillis() - DEDUPE_WINDOW_MS
        val similar = messageDao.countSimilarSince(
            packageName = message.packageName,
            sender = message.sender,
            text = message.text,
            sinceMs = sinceMs,
        )
        if (similar > 0) return false

        messageDao.insert(message)
        refreshUnreadCount()
        return true
    }

    suspend fun getUnread(): List<IncomingMessage> = messageDao.getUnread()

    suspend fun getRecent(limit: Int = 50): List<IncomingMessage> = messageDao.getRecent(limit)

    suspend fun markAllRead() {
        messageDao.markAllRead()
        refreshUnreadCount()
    }

    suspend fun refreshUnreadCount() {
        _unreadCount.value = messageDao.countUnread()
    }

    suspend fun deleteMessage(id: Long) {
        messageDao.deleteById(id)
        refreshUnreadCount()
    }

    suspend fun deleteAllMessages() {
        messageDao.deleteAll()
        refreshUnreadCount()
    }

    suspend fun buildSpokenSummary(): String {
        val unread = dedupeForSpeech(getUnread())
        if (unread.isEmpty()) {
            return "No tienes mensajes nuevos."
        }
        replyContext.updateFromMessages(unread)
        val parts = unread.map { msg ->
            "${msg.appLabel} de ${msg.sender}: ${msg.text}"
        }
        markAllRead()
        return if (unread.size == 1) {
            "Tienes 1 mensaje nuevo. ${parts.first()}."
        } else {
            "Tienes ${unread.size} mensajes nuevos. ${parts.joinToString(". ")}."
        }
    }

    /** Una sola lectura por contacto+texto, aunque WhatsApp haya notificado dos veces. */
    private fun dedupeForSpeech(messages: List<IncomingMessage>): List<IncomingMessage> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<IncomingMessage>(messages.size)
        for (msg in messages) {
            if (isNoiseNotification(msg.sender, msg.text)) continue
            val key = normalizeKey(msg.packageName, msg.sender, msg.text)
            if (!seen.add(key)) continue
            out.add(msg)
        }
        return out
    }

    companion object {
        private const val DEDUPE_WINDOW_MS = 10 * 60_000L

        fun isNoiseNotification(sender: String, text: String): Boolean {
            val t = text.trim()
            if (t.isBlank()) return true
            val n = normalizeLoose(t)
            if (n.contains("mensajes de")) return true
            if (n.contains("tocar para") || n.contains("pulse para")) return true
            if (n.contains("checking for new messages")) return true
            if (Regex("""^\d+\s+mensajes?(?:\s+nuevos?)?$""").containsMatchIn(n)) return true
            if (Regex("""^\d+\s+new\s+messages?$""").containsMatchIn(n)) return true
            val s = normalizeLoose(sender)
            if (s == "whatsapp" || s == "whatsapp business") {
                if (n.contains("mensaje") && n.length < 40) return true
            }
            return false
        }

        fun normalizeKey(packageName: String, sender: String, text: String): String {
            return listOf(
                packageName.trim().lowercase(),
                normalizeLoose(sender),
                normalizeLoose(text),
            ).joinToString("|")
        }

        private fun normalizeLoose(value: String): String {
            return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase()
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }
}
