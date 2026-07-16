package io.lazaro.assistant

import javax.inject.Inject
import javax.inject.Singleton

data class ConversationTurn(
    val userMessage: String,
    val assistantMessage: String,
    val sessionMarker: String? = null,
)

@Singleton
class ConversationContext @Inject constructor() {
    var lastPrompt: String = ""
        private set

    var lastUserMessage: String = ""
        private set

    var pendingHint: String = ""
        private set

    private val recentTurns = ArrayDeque<ConversationTurn>()

    fun recordTurn(
        userMessage: String,
        assistantMessage: String,
        sessionMarker: String? = null,
    ) {
        if (userMessage.isNotBlank()) lastUserMessage = userMessage
        if (assistantMessage.isNotBlank()) lastPrompt = assistantMessage
        if (userMessage.isBlank() && assistantMessage.isBlank()) return
        recentTurns.addLast(
            ConversationTurn(
                userMessage = userMessage,
                assistantMessage = assistantMessage,
                sessionMarker = sessionMarker,
            ),
        )
        while (recentTurns.size > MAX_TURNS) {
            recentTurns.removeFirst()
        }
    }

    fun recordPending(hint: String, prompt: String) {
        pendingHint = hint
        if (prompt.isNotBlank()) lastPrompt = prompt
    }

    fun clearPending() {
        pendingHint = ""
    }

    fun hasPendingContext(): Boolean = pendingHint.isNotBlank() || lastPrompt.isNotBlank()

    fun recentTurns(): List<ConversationTurn> = recentTurns.toList()

    fun formatRecentHistory(): String {
        if (recentTurns.isEmpty()) return ""
        return buildString {
            appendLine("=== CONVERSACIÓN RECIENTE ===")
            recentTurns.forEach { turn ->
                val marker = turn.sessionMarker?.let { " $it" }.orEmpty()
                if (turn.userMessage.isNotBlank()) {
                    appendLine("Usuario$marker: ${turn.userMessage}")
                }
                if (turn.assistantMessage.isNotBlank()) {
                    appendLine("Lazaro: ${turn.assistantMessage}")
                }
            }
            append("=== FIN CONVERSACIÓN ===")
        }
    }

    companion object {
        const val MAX_TURNS = 6
    }
}
