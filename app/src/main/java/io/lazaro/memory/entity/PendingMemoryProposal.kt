package io.lazaro.memory.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_memory_proposals")
data class PendingMemoryProposal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val proposalType: String,
    val proposedKey: String,
    val proposedValue: String,
    val triggerPhrases: String = "",
    val actionType: String = "",
    val actionPayload: String = "",
    val reason: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

object ProposalType {
    const val MEMORY = "memory"
    const val SKILL = "skill"
}
