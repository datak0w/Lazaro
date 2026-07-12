package io.lazaro.memory.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "custom_skills")
data class CustomSkill(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val triggerPhrases: String,
    val actionType: String,
    val actionPayload: String,
    val description: String = "",
    val confirmed: Boolean = false,
    val useCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

object SkillActionType {
    const val OPEN_APP = "open_app"
    const val CALL_PHONE = "call_phone"
    const val NAVIGATE = "navigate"
    const val OPEN_URL = "open_url"
    const val RECALL_MEMORY = "recall_memory"
}
