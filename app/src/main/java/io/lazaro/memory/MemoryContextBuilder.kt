package io.lazaro.memory

import io.lazaro.assistant.ActiveSessionTracker
import io.lazaro.actions.ActionExecutor
import io.lazaro.memory.entity.CustomSkill
import io.lazaro.memory.entity.MemoryCategory
import io.lazaro.memory.entity.MemoryEntry
import io.lazaro.messaging.MessageRepository
import io.lazaro.pathguide.PathGuideController
import io.lazaro.routes.RouteRepository
import io.lazaro.routes.recording.RouteRecorderController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryContextBuilder @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val messageRepository: MessageRepository,
    private val savedPlaceRepository: SavedPlaceRepository,
    private val routeRepository: RouteRepository,
    private val pathGuideController: PathGuideController,
    private val routeRecorderController: RouteRecorderController,
    private val activeSessionTracker: ActiveSessionTracker,
    private val actionExecutor: ActionExecutor,
    private val locationTracker: LocationTracker,
) {
    suspend fun buildContextBlock(): String {
        val memories = rankMemories(memoryRepository.getAllMemories())
        val skills = memoryRepository.getConfirmedSkills()
        val recentLocations = memoryRepository.getRecentLocations(5)
        val unreadMessages = messageRepository.getUnread().size
        val places = savedPlaceRepository.getAllPlaces()
        val routes = routeRepository.getAllRoutes()
        val locationLine = locationTracker.describeCurrentLocationBrief()
        val now = SimpleDateFormat("HH:mm", Locale("es", "ES")).format(Date())
        val pathMode = pathGuideController.currentMode()
        val recording = routeRecorderController.isRecording() ||
            routeRecorderController.isCapturingSamples()

        return buildString {
            appendLine("=== ESTADO AHORA ===")
            appendLine("Hora local: $now.")
            appendLine("PathGuide: ${pathMode.name}.")
            if (recording) appendLine("Grabando muestras de ruta: sí.")
            if (actionExecutor.hasPendingConfirmation()) {
                appendLine("Hay una confirmación pendiente: ${actionExecutor.getPendingHint()}.")
            }
            if (unreadMessages > 0) {
                appendLine("Mensajes sin leer: $unreadMessages. Ofrece leerlos si es oportuno.")
            }
            appendLine("Ubicación aproximada: $locationLine")
            appendLine(activeSessionTracker.formatForPrompt())
            appendLine("=== FIN ESTADO ===")

            appendLine("=== MEMORIA DEL CLIENTE (usar en respuestas y acciones) ===")
            if (places.isNotEmpty()) {
                appendLine("Sitios favoritos:")
                places.take(12).forEach { place ->
                    val addr = place.address?.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
                    appendLine("- ${place.displayName}$addr")
                }
            }
            if (routes.isNotEmpty()) {
                appendLine("Rutas grabadas:")
                routes.take(12).forEach { route ->
                    appendLine("- ${route.name}")
                }
            }
            if (memories.isNotEmpty()) {
                appendLine("Datos conocidos:")
                memories.forEach { appendLine(formatMemory(it)) }
            }
            if (skills.isNotEmpty()) {
                appendLine("Skills personalizados:")
                skills.forEach { appendLine(formatSkill(it)) }
            }
            if (recentLocations.isNotEmpty()) {
                appendLine("Últimos lugares visitados:")
                recentLocations.forEach { loc ->
                    val label = loc.label ?: loc.address ?: "${loc.latitude}, ${loc.longitude}"
                    appendLine("- $label")
                }
            }
            if (places.isEmpty() && routes.isEmpty() && memories.isEmpty() &&
                skills.isEmpty() && recentLocations.isEmpty() && unreadMessages == 0
            ) {
                appendLine("Aún no hay datos guardados del cliente.")
            }
            appendLine("=== FIN MEMORIA ===")
            appendLine(
                "Si el usuario enseña algo nuevo recurrente (dirección, contacto, preferencia, frase→acción), " +
                    "usa save_memory o create_skill y pide confirmación.",
            )
            if (activeSessionTracker.isPausedForChat()) {
                appendLine(
                    "IMPORTANTE: hay una sesión pausada. Tras resolver la petición actual, " +
                        "pregunta si quiere reanudarla (o usa resume_active_session).",
                )
            }
        }
    }

    private fun rankMemories(all: List<MemoryEntry>): List<MemoryEntry> {
        return all
            .sortedByDescending { it.updatedAt }
            .groupBy { it.category }
            .flatMap { (_, entries) -> entries.take(6) }
            .sortedByDescending { it.updatedAt }
            .take(MAX_MEMORIES_IN_PROMPT)
    }

    private fun formatMemory(entry: MemoryEntry): String {
        val aliases = entry.aliases.takeIf { it.isNotBlank() }?.let { " (también: $it)" }.orEmpty()
        if (entry.category == MemoryCategory.PLACE) {
            val place = SavedPlaceCodec.fromEntry(entry)
            if (place != null) {
                val addr = place.address?.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
                return "- sitio ${place.displayName}$addr$aliases [place]"
            }
        }
        return "- ${entry.key}: ${entry.value}$aliases [${entry.category}]"
    }

    private fun formatSkill(skill: CustomSkill): String {
        val triggers = memoryRepository.parseTriggerPhrases(skill.triggerPhrases).joinToString(", ")
        return "- ${skill.name}: cuando diga \"$triggers\" → ${skill.actionType} ${skill.actionPayload}"
    }

    companion object {
        private const val MAX_MEMORIES_IN_PROMPT = 20
    }
}
