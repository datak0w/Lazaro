package io.lazaro.assistant

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationContext @Inject constructor() {
    var lastPrompt: String = ""
        private set

    var lastUserMessage: String = ""
        private set

    var pendingHint: String = ""
        private set

    fun recordTurn(userMessage: String, assistantMessage: String) {
        if (userMessage.isNotBlank()) lastUserMessage = userMessage
        if (assistantMessage.isNotBlank()) lastPrompt = assistantMessage
    }

    fun recordPending(hint: String, prompt: String) {
        pendingHint = hint
        if (prompt.isNotBlank()) lastPrompt = prompt
    }

    fun clearPending() {
        pendingHint = ""
    }

    fun hasPendingContext(): Boolean = pendingHint.isNotBlank() || lastPrompt.isNotBlank()
}
