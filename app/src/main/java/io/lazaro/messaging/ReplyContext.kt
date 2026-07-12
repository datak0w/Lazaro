package io.lazaro.messaging

import io.lazaro.messaging.entity.IncomingMessage
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReplyContext @Inject constructor() {
    var lastSender: String? = null
    var lastSenderPackage: String? = null
    var lastMessages: List<IncomingMessage> = emptyList()

    fun updateFromMessages(messages: List<IncomingMessage>) {
        lastMessages = messages
        lastSender = messages.lastOrNull()?.sender
        lastSenderPackage = messages.lastOrNull()?.packageName
    }

    fun resolveRecipient(explicitRecipient: String?): String? {
        if (!explicitRecipient.isNullOrBlank()) return explicitRecipient.trim()
        return lastSender
    }
}
