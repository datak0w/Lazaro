package io.lazaro.ai

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import io.lazaro.BuildConfig
import io.lazaro.memory.MemoryRepository
import io.lazaro.memory.entity.PendingMemoryProposal
import io.lazaro.memory.entity.ProposalType
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryExtractor @Inject constructor(
    private val memoryRepository: MemoryRepository,
) {
    private val model: GenerativeModel? by lazy {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank()) return@lazy null
        GeminiConfig.createModel(
            apiKey = apiKey,
            systemInstruction = content {
                text(
                    """
                    Eres un extractor de memoria para un asistente de voz de personas ciegas.
                    Analiza conversaciones y detecta datos recurrentes que valga la pena recordar.
                    Responde SOLO con JSON válido, sin markdown.
                    Formato:
                    {"items":[{"type":"memory","key":"home_address","value":"Calle Mayor 5","category":"address","aliases":"casa|mi casa","confidence":0.9}]}
                    o {"items":[{"type":"skill","name":"Radio COPE","trigger_phrases":"pon la radio|radio","action_type":"open_app","action_payload":"{\"package\":\"com.cope.app\"}","confidence":0.85}]}
                    Si no hay nada que aprender: {"items":[]}
                    Reglas:
                    - Solo hechos concretos (direcciones, teléfonos, preferencias, frases→acciones).
                    - confidence >= 0.75 para incluir.
                    - No repetir datos que ya existen en memoria conocida.
                    - Máximo 2 items por conversación; elige los más útiles.
                    """.trimIndent(),
                )
            },
        )
    }

    suspend fun extractFromConversation(
        userText: String,
        assistantText: String,
        existingMemoryContext: String,
    ): PendingMemoryProposal? {
        if (userText.isBlank()) return null
        val generativeModel = model ?: return null

        val prompt = """
            Memoria existente:
            $existingMemoryContext

            Conversación:
            Usuario: $userText
            Asistente: $assistantText

            ¿Hay algo nuevo que recordar? JSON:
        """.trimIndent()

        return try {
            val response = generativeModel.generateContent(prompt).text?.trim().orEmpty()
            parseResponse(response)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun parseResponse(raw: String): PendingMemoryProposal? {
        val jsonText = raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val root = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            return null
        }

        val items = root.optJSONArray("items") ?: return null
        if (items.length() == 0) return null

        // Prefer first valid item with confidence >= 0.75
        for (i in 0 until minOf(items.length(), 2)) {
            val item = items.getJSONObject(i)
            val confidence = item.optDouble("confidence", 0.0)
            if (confidence < 0.75) continue
            val proposal = parseItem(item) ?: continue
            return proposal
        }
        return null
    }

    private suspend fun parseItem(item: JSONObject): PendingMemoryProposal? {
        return when (item.optString("type")) {
            "memory" -> {
                val key = item.optString("key")
                val value = item.optString("value")
                if (key.isBlank() || value.isBlank()) return null
                if (memoryRepository.getMemory(key) != null) return null
                if (memoryRepository.resolveMemoryValue(key) == value) return null

                PendingMemoryProposal(
                    proposalType = ProposalType.MEMORY,
                    proposedKey = key,
                    proposedValue = value,
                    triggerPhrases = item.optString("aliases", ""),
                    reason = "Detectado automáticamente en conversación",
                )
            }
            "skill" -> {
                val name = item.optString("name")
                val triggers = item.optString("trigger_phrases")
                val actionType = item.optString("action_type")
                val payload = item.optString("action_payload")
                if (name.isBlank() || triggers.isBlank() || actionType.isBlank() || payload.isBlank()) {
                    return null
                }
                PendingMemoryProposal(
                    proposalType = ProposalType.SKILL,
                    proposedKey = name,
                    proposedValue = payload,
                    triggerPhrases = triggers,
                    actionType = actionType,
                    actionPayload = payload,
                    reason = item.optString("skill_description", "Skill detectado en conversación"),
                )
            }
            else -> null
        }
    }

    fun buildProposalQuestion(proposal: PendingMemoryProposal): String {
        return when (proposal.proposalType) {
            ProposalType.MEMORY -> {
                val aliasHint = proposal.triggerPhrases.takeIf { it.isNotBlank() }
                    ?.let { " También lo pillo si dices $it." }
                    .orEmpty()
                "Oye, he pillado algo útil: ¿guardo que ${proposal.proposedKey} es ${proposal.proposedValue}?$aliasHint Di sí o no."
            }
            ProposalType.SKILL -> {
                val trigger = proposal.triggerPhrases.split("|").firstOrNull().orEmpty()
                "Esto mola: ¿quieres que cuando digas \"$trigger\" haga ${proposal.proposedKey} solo? Di sí o no."
            }
            else -> "¿Guardo esto en memoria por si acaso? Di sí o no."
        }
    }
}
