package io.lazaro.memory

import io.lazaro.memory.dao.LocationDao
import io.lazaro.memory.dao.MemoryDao
import io.lazaro.memory.dao.ProposalDao
import io.lazaro.memory.dao.SkillDao
import io.lazaro.memory.entity.CustomSkill
import io.lazaro.memory.entity.LocationRecord
import io.lazaro.memory.entity.MemoryCategory
import io.lazaro.memory.entity.MemoryEntry
import io.lazaro.memory.entity.PendingMemoryProposal
import io.lazaro.memory.entity.ProposalType
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryRepository @Inject constructor(
    private val memoryDao: MemoryDao,
    private val skillDao: SkillDao,
    private val locationDao: LocationDao,
    private val proposalDao: ProposalDao,
) {
    suspend fun getAllMemories(): List<MemoryEntry> = memoryDao.getAllMemories()

    suspend fun getMemory(key: String): MemoryEntry? = memoryDao.getMemory(key)

    suspend fun getAllSkills(): List<CustomSkill> = skillDao.getAllSkills()

    suspend fun deleteMemory(key: String) = memoryDao.deleteMemory(key)

    suspend fun deleteSkill(id: Long) = skillDao.deleteSkill(id)

    suspend fun deleteLocation(id: Long) = locationDao.deleteLocation(id)

    suspend fun saveMemory(
        key: String,
        value: String,
        category: String = MemoryCategory.CUSTOM,
        aliases: List<String> = emptyList(),
        notes: String = "",
        source: String = "user_stated",
    ) {
        val existing = memoryDao.getMemory(key)
        memoryDao.upsertMemory(
            MemoryEntry(
                key = key,
                value = value,
                category = category,
                aliases = aliases.joinToString("|"),
                notes = notes,
                source = source,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun resolveMemoryValue(keyOrAlias: String): String? {
        val direct = memoryDao.getMemory(keyOrAlias)
        if (direct != null) return direct.value

        return memoryDao.getAllMemories().find { entry ->
            entry.aliases.split("|").any { it.equals(keyOrAlias, ignoreCase = true) }
        }?.value
    }

    suspend fun getConfirmedSkills(): List<CustomSkill> = skillDao.getConfirmedSkills()

    suspend fun saveSkill(
        name: String,
        triggerPhrases: List<String>,
        actionType: String,
        actionPayload: String,
        description: String = "",
        confirmed: Boolean = false,
    ): Long {
        return skillDao.upsertSkill(
            CustomSkill(
                name = name,
                triggerPhrases = JSONArray(triggerPhrases).toString(),
                actionType = actionType,
                actionPayload = actionPayload,
                description = description,
                confirmed = confirmed,
            ),
        )
    }

    suspend fun confirmSkill(skillId: Long) {
        val skills = skillDao.getAllSkills()
        val skill = skills.find { it.id == skillId } ?: return
        skillDao.upsertSkill(skill.copy(confirmed = true, updatedAt = System.currentTimeMillis()))
    }

    suspend fun incrementSkillUse(skillId: Long) {
        skillDao.incrementUseCount(skillId)
    }

    suspend fun recordLocation(
        latitude: Double,
        longitude: Double,
        address: String? = null,
        label: String? = null,
        source: String = "periodic",
    ) {
        locationDao.insert(
            LocationRecord(
                latitude = latitude,
                longitude = longitude,
                address = address,
                label = label,
                source = source,
            ),
        )
    }

    suspend fun getRecentLocations(limit: Int = 20): List<LocationRecord> =
        locationDao.getRecent(limit)

    suspend fun getLocationTrail(hours: Int = 24): List<LocationRecord> {
        val since = System.currentTimeMillis() - hours * 60L * 60L * 1000L
        return locationDao.getSince(since)
    }

    suspend fun saveProposal(proposal: PendingMemoryProposal): Long =
        proposalDao.insert(proposal)

    suspend fun getLatestProposal(): PendingMemoryProposal? = proposalDao.getLatest()

    suspend fun clearProposals() = proposalDao.clearAll()

    suspend fun confirmLatestProposal(): String? {
        val proposal = proposalDao.getLatest() ?: return null
        when (proposal.proposalType) {
            ProposalType.MEMORY -> {
                saveMemory(
                    key = proposal.proposedKey,
                    value = proposal.proposedValue,
                    category = MemoryCategory.CUSTOM,
                    aliases = proposal.triggerPhrases.split("|").filter { it.isNotBlank() },
                    source = "ai_learned",
                )
            }
            ProposalType.SKILL -> {
                saveSkill(
                    name = proposal.proposedKey,
                    triggerPhrases = proposal.triggerPhrases.split("|").filter { it.isNotBlank() },
                    actionType = proposal.actionType,
                    actionPayload = proposal.actionPayload,
                    description = proposal.reason,
                    confirmed = true,
                )
            }
        }
        proposalDao.clearAll()
        return when (proposal.proposalType) {
            ProposalType.MEMORY -> "Hecho. Recordaré que ${proposal.proposedKey} es ${proposal.proposedValue}."
            ProposalType.SKILL -> "Hecho. La próxima vez que digas ${proposal.triggerPhrases.split("|").firstOrNull()}, lo haré automáticamente."
            else -> "Guardado."
        }
    }

    suspend fun rejectLatestProposal(): String {
        proposalDao.clearAll()
        return "De acuerdo, no lo guardaré."
    }

    fun parseTriggerPhrases(jsonOrPipe: String): List<String> {
        return try {
            val arr = JSONArray(jsonOrPipe)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            jsonOrPipe.split("|", ",").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
}
