package io.lazaro.assistant

import javax.inject.Inject
import javax.inject.Singleton

enum class ActiveSessionKind {
    NAVIGATION,
    ROUTE_REPLAY,
    WALK,
    RECORDING,
}

enum class ActiveSessionPhase {
    RUNNING,
    PAUSED_FOR_CHAT,
}

data class ActiveSessionSnapshot(
    val kind: ActiveSessionKind,
    val label: String,
    val phase: ActiveSessionPhase,
    val startedAtMs: Long,
    val lastManeuverHint: String? = null,
    val awaitingResumePrompt: Boolean = false,
) {
    fun kindLabelEs(): String = when (kind) {
        ActiveSessionKind.NAVIGATION -> "navegación a pie"
        ActiveSessionKind.ROUTE_REPLAY -> "ruta grabada"
        ActiveSessionKind.WALK -> "modo paseo"
        ActiveSessionKind.RECORDING -> "grabación de ruta"
    }

    fun resumeQuestion(): String {
        val target = label.ifBlank { "el destino" }
        return when (kind) {
            ActiveSessionKind.NAVIGATION,
            ActiveSessionKind.ROUTE_REPLAY,
            -> "¿Seguimos hacia $target?"
            ActiveSessionKind.WALK -> "¿Seguimos con el paseo?"
            ActiveSessionKind.RECORDING -> "¿Seguimos grabando la ruta?"
        }
    }
}

/**
 * Estado de la acción larga en curso (nav / ruta / paseo / grabación)
 * para que el chat sepa qué hay abierto y pueda ofrecer reanudar.
 */
@Singleton
class ActiveSessionTracker @Inject constructor() {
    @Volatile
    private var session: ActiveSessionSnapshot? = null

    fun snapshot(): ActiveSessionSnapshot? = session

    fun hasActiveSession(): Boolean = session != null

    fun isPausedForChat(): Boolean = session?.phase == ActiveSessionPhase.PAUSED_FOR_CHAT

    fun start(kind: ActiveSessionKind, label: String) {
        session = ActiveSessionSnapshot(
            kind = kind,
            label = label.trim().ifBlank { defaultLabel(kind) },
            phase = ActiveSessionPhase.RUNNING,
            startedAtMs = System.currentTimeMillis(),
        )
    }

    fun updateLabel(label: String) {
        val current = session ?: return
        if (label.isBlank()) return
        session = current.copy(label = label.trim())
    }

    fun setLastManeuverHint(hint: String?) {
        val current = session ?: return
        session = current.copy(lastManeuverHint = hint?.takeIf { it.isNotBlank() })
    }

    fun pauseForChat(): ActiveSessionSnapshot? {
        val current = session ?: return null
        session = current.copy(phase = ActiveSessionPhase.PAUSED_FOR_CHAT, awaitingResumePrompt = false)
        return session
    }

    fun resumeFromChat(): ActiveSessionSnapshot? {
        val current = session ?: return null
        session = current.copy(phase = ActiveSessionPhase.RUNNING, awaitingResumePrompt = false)
        return session
    }

    fun markAwaitingResumePrompt(awaiting: Boolean) {
        val current = session ?: return
        session = current.copy(awaitingResumePrompt = awaiting)
    }

    fun clear() {
        session = null
    }

    fun formatForPrompt(): String {
        val current = session ?: return "No hay sesión larga activa."
        val phaseEs = when (current.phase) {
            ActiveSessionPhase.RUNNING -> "en curso"
            ActiveSessionPhase.PAUSED_FOR_CHAT -> "pausada para chat"
        }
        return buildString {
            append("Sesión activa: ${current.kindLabelEs()} → «${current.label}» ($phaseEs).")
            current.lastManeuverHint?.let { append(" Última instrucción Maps: $it.") }
            if (current.phase == ActiveSessionPhase.PAUSED_FOR_CHAT) {
                append(" El usuario puede dar otras órdenes. Al terminar una orden lateral, pregunta si reanuda.")
            }
        }
    }

    fun historyMarker(): String? {
        val current = session ?: return null
        val short = when (current.kind) {
            ActiveSessionKind.NAVIGATION -> "nav"
            ActiveSessionKind.ROUTE_REPLAY -> "ruta"
            ActiveSessionKind.WALK -> "paseo"
            ActiveSessionKind.RECORDING -> "grabando"
        }
        val pause = if (current.phase == ActiveSessionPhase.PAUSED_FOR_CHAT) "|pausada" else ""
        return "[$short→${current.label}$pause]"
    }

    private fun defaultLabel(kind: ActiveSessionKind): String = when (kind) {
        ActiveSessionKind.NAVIGATION -> "destino"
        ActiveSessionKind.ROUTE_REPLAY -> "ruta"
        ActiveSessionKind.WALK -> "paseo"
        ActiveSessionKind.RECORDING -> "ruta nueva"
    }
}
