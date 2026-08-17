package io.lazaro.assistant

import io.lazaro.memory.MemoryRepository
import io.lazaro.memory.SavedPlaceRepository
import io.lazaro.messaging.MessageRepository
import io.lazaro.pathguide.PathGuideController
import io.lazaro.pathguide.PathGuideMode
import io.lazaro.routes.RouteRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sugerencias locales (sin LLM) en momentos concretos: wake vacío, post-orden, idle.
 */
@Singleton
class ProactiveSuggestionEngine @Inject constructor(
    private val messageRepository: MessageRepository,
    private val savedPlaceRepository: SavedPlaceRepository,
    private val routeRepository: RouteRepository,
    private val pathGuideController: PathGuideController,
    private val activeSessionTracker: ActiveSessionTracker,
    private val sleepModeController: SleepModeController,
) {
    @Volatile
    private var lastSuggestionMs = 0L

    suspend fun suggestionAfterWakeWithoutCommand(): String? {
        if (!canSuggest()) return null
        activeSessionTracker.snapshot()?.takeIf {
            it.phase == ActiveSessionPhase.PAUSED_FOR_CHAT
        }?.let {
            markSuggested()
            return it.resumeQuestion()
        }
        val unread = messageRepository.getUnread().size
        if (unread > 0) {
            markSuggested()
            return if (unread == 1) {
                "Tienes 1 WhatsApp sin leer. ¿Lo leo?"
            } else {
                "Tienes $unread WhatsApp sin leer. ¿Los leo?"
            }
        }
        return inventoryOffer()
    }

    suspend fun suggestionAfterSideOrder(): String? {
        val session = activeSessionTracker.snapshot() ?: return null
        if (session.phase != ActiveSessionPhase.PAUSED_FOR_CHAT) return null
        if (!canSuggest(minGapMs = SIDE_ORDER_GAP_MS)) return null
        markSuggested()
        activeSessionTracker.markAwaitingResumePrompt(true)
        return session.resumeQuestion()
    }

    suspend fun suggestionForIdleGreeting(): String? {
        if (!canSuggest()) return null
        return inventoryOffer()
    }

    private suspend fun inventoryOffer(): String? {
        val routes = routeRepository.getAllRoutes()
        val places = savedPlaceRepository.getAllPlaces()
        val pathOff = pathGuideController.currentMode() == PathGuideMode.OFF
        val homeRoute = routes.firstOrNull { it.name.contains("casa", ignoreCase = true) }
        val homePlace = places.firstOrNull {
            it.displayName.contains("casa", ignoreCase = true) ||
                it.key.contains("casa", ignoreCase = true)
        }
        if (pathOff && homeRoute != null) {
            markSuggested()
            return "¿Te llevo a ${homeRoute.name} con la ruta guardada?"
        }
        if (pathOff && homePlace != null) {
            markSuggested()
            return "¿Te guío hasta ${homePlace.displayName}?"
        }
        val options = buildList {
            routes.firstOrNull()?.let { add(it.name) }
            places.firstOrNull()?.let { add(it.displayName) }
        }.distinct().take(2)
        if (options.isEmpty()) return null
        markSuggested()
        return when (options.size) {
            1 -> "¿Quieres que te lleve a ${options[0]}?"
            else -> "¿Quieres ir a ${options[0]} o a ${options[1]}?"
        }
    }

    private fun canSuggest(minGapMs: Long = DEBOUNCE_MS): Boolean {
        if (sleepModeController.isSleeping()) return false
        return System.currentTimeMillis() - lastSuggestionMs >= minGapMs
    }

    private fun markSuggested() {
        lastSuggestionMs = System.currentTimeMillis()
    }

    companion object {
        /** Tips espontáneos: como mucho cada 10 min. */
        private const val DEBOUNCE_MS = 600_000L
        /** Oferta «¿Seguimos…?» tras orden lateral. */
        private const val SIDE_ORDER_GAP_MS = 30_000L
    }
}
