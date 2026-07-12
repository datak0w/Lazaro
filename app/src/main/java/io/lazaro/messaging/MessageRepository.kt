package io.lazaro.messaging

import io.lazaro.messaging.dao.MessageDao
import io.lazaro.messaging.entity.IncomingMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepository @Inject constructor(
    private val messageDao: MessageDao,
    private val replyContext: ReplyContext,
) {
    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    suspend fun addMessage(message: IncomingMessage) {
        messageDao.insert(message)
        refreshUnreadCount()
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
        val unread = getUnread()
        if (unread.isEmpty()) {
            return "No tienes mensajes nuevos."
        }
        replyContext.updateFromMessages(unread)
        val parts = unread.map { msg ->
            "${msg.appLabel} de ${msg.sender}: ${msg.text}"
        }
        markAllRead()
        return if (unread.size == 1) {
            "Tienes 1 mensaje nuevo. ${parts.first()}. Puedes decir: responde que..."
        } else {
            "Tienes ${unread.size} mensajes nuevos. ${parts.joinToString(". ")}. Puedes decir: responde a [nombre] que..."
        }
    }
}
