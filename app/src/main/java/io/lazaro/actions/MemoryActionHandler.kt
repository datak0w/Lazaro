package io.lazaro.actions

import io.lazaro.memory.LocationTracker
import io.lazaro.memory.MemoryRepository
import io.lazaro.memory.entity.MemoryCategory
import io.lazaro.memory.entity.ProposalType
import io.lazaro.memory.entity.PendingMemoryProposal
import io.lazaro.memory.entity.SkillActionType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryActionHandler @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val locationTracker: LocationTracker,
) {
    suspend fun saveMemory(args: Map<String, String>): ActionResult {
        val key = args["key"].orEmpty()
        val value = args["value"].orEmpty()
        val category = args["category"].orEmpty().ifBlank { MemoryCategory.CUSTOM }
        val aliases = args["aliases"].orEmpty().split("|", ",").map { it.trim() }.filter { it.isNotEmpty() }

        if (key.isBlank() || value.isBlank()) {
            return ActionResult.Error("Necesito saber qué guardar.")
        }

        memoryRepository.saveProposal(
            PendingMemoryProposal(
                proposalType = ProposalType.MEMORY,
                proposedKey = key,
                proposedValue = value,
                triggerPhrases = aliases.joinToString("|"),
                reason = "Memoria propuesta: $key = $value",
            ),
        )
        return ActionResult.NeedsConfirmation(
            prompt = "¿Quieres que recuerde que $key es $value?",
            pendingAction = PendingAction("confirm_memory", emptyMap()),
        )
    }

    suspend fun createSkill(args: Map<String, String>): ActionResult {
        val name = args["name"].orEmpty()
        val triggers = args["trigger_phrases"].orEmpty()
        val actionType = args["action_type"].orEmpty()
        val payload = args["action_payload"].orEmpty()

        if (name.isBlank() || triggers.isBlank() || actionType.isBlank() || payload.isBlank()) {
            return ActionResult.Error("Faltan datos para crear el skill.")
        }

        memoryRepository.saveProposal(
            PendingMemoryProposal(
                proposalType = ProposalType.SKILL,
                proposedKey = name,
                proposedValue = payload,
                triggerPhrases = triggers,
                actionType = actionType,
                actionPayload = payload,
                reason = args["skill_description"].orEmpty().ifBlank { args["description"].orEmpty() },
            ),
        )
        val firstTrigger = triggers.split("|", ",").firstOrNull().orEmpty()
        return ActionResult.NeedsConfirmation(
            prompt = "¿Quieres que cuando digas \"$firstTrigger\" haga $name automáticamente?",
            pendingAction = PendingAction("confirm_skill", emptyMap()),
        )
    }

    suspend fun recallMemory(key: String): ActionResult {
        if (key.isBlank()) return ActionResult.Error("¿Qué dato quieres que recuerde?")
        val value = memoryRepository.resolveMemoryValue(key)
            ?: return ActionResult.Error("No tengo guardado nada sobre $key.")
        return ActionResult.Success(value)
    }

    suspend fun getLocationTrail(hours: String?): ActionResult {
        val h = hours?.toIntOrNull()?.coerceIn(1, 48) ?: 6
        return ActionResult.Success(locationTracker.describeTrailForLostUser(h))
    }

    suspend fun confirmMemoryProposal(): ActionResult {
        val message = memoryRepository.confirmLatestProposal()
            ?: return ActionResult.Error("No hay nada pendiente de guardar.")
        return ActionResult.Success(message)
    }

    suspend fun rejectMemoryProposal(): ActionResult {
        return ActionResult.Success(memoryRepository.rejectLatestProposal())
    }

    suspend fun navigateUsingMemory(destinationKey: String): String? {
        return memoryRepository.resolveMemoryValue(destinationKey)
    }
}
