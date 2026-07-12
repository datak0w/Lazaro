package io.lazaro.memory

import io.lazaro.memory.entity.CustomSkill
import io.lazaro.memory.entity.MemoryEntry
import io.lazaro.messaging.MessageRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryContextBuilder @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val messageRepository: MessageRepository,
) {
    suspend fun buildContextBlock(): String {
        val memories = memoryRepository.getAllMemories()
        val skills = memoryRepository.getConfirmedSkills()
        val recentLocations = memoryRepository.getRecentLocations(5)
        val unreadMessages = messageRepository.getUnread().size

        if (memories.isEmpty() && skills.isEmpty() && recentLocations.isEmpty() && unreadMessages == 0) {
            return "No hay datos guardados del cliente todavía."
        }

        return buildString {
            appendLine("=== MEMORIA DEL CLIENTE (usar en respuestas y acciones) ===")
            if (unreadMessages > 0) {
                appendLine("Mensajes sin leer: $unreadMessages. Ofrece leerlos si es oportuno.")
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
                    appendLine("- $label (${loc.visitedAt})")
                }
            }
            appendLine("=== FIN MEMORIA ===")
            appendLine(
                "Si el usuario enseña algo nuevo recurrente (dirección, contacto, preferencia, frase→acción), " +
                    "usa save_memory o create_skill y pide confirmación.",
            )
        }
    }

    private fun formatMemory(entry: MemoryEntry): String {
        val aliases = entry.aliases.takeIf { it.isNotBlank() }?.let { " (también: $it)" }.orEmpty()
        return "- ${entry.key}: ${entry.value}$aliases [${entry.category}]"
    }

    private fun formatSkill(skill: CustomSkill): String {
        val triggers = memoryRepository.parseTriggerPhrases(skill.triggerPhrases).joinToString(", ")
        return "- ${skill.name}: cuando diga \"$triggers\" → ${skill.actionType} ${skill.actionPayload}"
    }
}
