package io.lazaro.ai

import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.FunctionCallPart
import com.google.ai.client.generativeai.type.GenerateContentResponse
import com.google.ai.client.generativeai.type.content
import io.lazaro.BuildConfig
import io.lazaro.actions.ActionExecutor
import io.lazaro.actions.ActionResult
import io.lazaro.actions.ToolName
import io.lazaro.assistant.ContextIntent
import io.lazaro.assistant.ContextIntentDetector
import io.lazaro.assistant.ConversationContext
import io.lazaro.memory.MemoryContextBuilder
import io.lazaro.memory.MemoryRepository
import io.lazaro.memory.SkillExecutor
import io.lazaro.memory.SkillMatcher
import javax.inject.Inject
import javax.inject.Singleton

data class AssistantReply(
    val spokenText: String,
    val actionTaken: Boolean = false,
    val skipAutoLearn: Boolean = false,
    val suspendListening: Boolean = false,
)

@Singleton
class GeminiOrchestrator @Inject constructor(
    private val actionExecutor: ActionExecutor,
    private val memoryContextBuilder: MemoryContextBuilder,
    private val skillMatcher: SkillMatcher,
    private val skillExecutor: SkillExecutor,
    private val memoryRepository: MemoryRepository,
    private val conversationContext: ConversationContext,
    private val contextIntentDetector: ContextIntentDetector,
) {
    private val model: GenerativeModel? by lazy {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank()) return@lazy null
        GeminiConfig.createModel(
            apiKey = apiKey,
            systemInstruction = content { text(SystemPrompt.ES) },
            tools = ToolDefinitions.all,
        )
    }

    fun hasApiKey(): Boolean = !BuildConfig.GEMINI_API_KEY.isBlank()

    suspend fun handleUserMessage(userText: String): AssistantReply {
        val cleaned = contextIntentDetector.stripWakeWord(userText).ifBlank { userText }
        if (cleaned.isBlank()) {
            return AssistantReply("¿Eh? No he pillado nada. Di Lazaro y suelta la que quieras.")
        }

        if (actionExecutor.hasPendingConfirmation()) {
            return handlePendingConversation(cleaned)
        }

        return handleFreeConversation(cleaned)
    }

    private fun repeatablePrompt(): String {
        return actionExecutor.getLastPromptText()
            .ifBlank { conversationContext.lastPrompt }
    }

    private suspend fun handlePendingConversation(userText: String): AssistantReply {
        when (contextIntentDetector.detect(userText, hasPending = true)) {
            ContextIntent.REPEAT_OPTIONS -> {
                val prompt = repeatablePrompt()
                return if (prompt.isBlank()) {
                    AssistantReply("No tengo opciones guardadas.", skipAutoLearn = true)
                } else {
                    AssistantReply(prompt, skipAutoLearn = true)
                }
            }
            ContextIntent.CANCEL_PENDING -> {
                return when (val result = actionExecutor.cancelPending()) {
                    is ActionResult.Success -> {
                        conversationContext.clearPending()
                        AssistantReply(result.message, skipAutoLearn = true)
                    }
                    is ActionResult.Error -> AssistantReply(result.message, skipAutoLearn = true)
                    is ActionResult.NeedsConfirmation -> AssistantReply(result.prompt, skipAutoLearn = true)
                }
            }
            ContextIntent.PENDING_HELP -> {
                return AssistantReply(
                    "Estoy esperando ${actionExecutor.getPendingHint()}. " +
                        "Responde con sí, no, un número o nombre. " +
                        "También puedes decir repíteme las opciones o cancela.",
                    skipAutoLearn = true,
                )
            }
            ContextIntent.NEW_COMMAND -> {
                actionExecutor.cancelPending()
                conversationContext.clearPending()
                val command = contextIntentDetector.stripWakeWord(userText)
                return handleFreeConversation(command)
            }
            ContextIntent.INTERRUPT -> {
                return AssistantReply("", skipAutoLearn = true, actionTaken = true)
            }
            null -> Unit
        }

        if (actionExecutor.isAffirmative(userText)) {
            return when (val result = actionExecutor.confirmPending()) {
                is ActionResult.Success -> {
                    conversationContext.clearPending()
                    successReply(result)
                }
                is ActionResult.Error -> AssistantReply(result.message, skipAutoLearn = true)
                is ActionResult.NeedsConfirmation -> {
                    recordPending(result.prompt, actionExecutor.getPendingHint())
                    AssistantReply(result.prompt, skipAutoLearn = true)
                }
            }
        }

        if (actionExecutor.isNegative(userText)) {
            return when (val result = actionExecutor.cancelPending()) {
                is ActionResult.Success -> {
                    conversationContext.clearPending()
                    AssistantReply(result.message, skipAutoLearn = true)
                }
                is ActionResult.Error -> AssistantReply(result.message, skipAutoLearn = true)
                is ActionResult.NeedsConfirmation -> AssistantReply(result.prompt, skipAutoLearn = true)
            }
        }

        actionExecutor.tryHandleContactSelection(userText)?.let { return toReply(it) }

        actionExecutor.tryHandleTimeIntent(userText)?.let { return toReply(it) }
        actionExecutor.tryHandleCalculatorIntent(userText)?.let { return toReply(it) }
        actionExecutor.tryHandleWeatherIntent(userText)?.let { return toReply(it) }
        actionExecutor.tryHandleReceiptIntent(userText)?.let { return toReply(it) }
        actionExecutor.tryHandleNewsIntent(userText)?.let { return toReply(it) }

        actionExecutor.tryHandleWalkIntent(userText)?.let { return toReply(it) }

        actionExecutor.tryHandleRouteIntent(userText)?.let { return toReply(it) }

        actionExecutor.tryHandleSavedPlaceIntent(userText)?.let { return toReply(it) }

        actionExecutor.tryHandleNavigationIntent(userText)?.let { return toReply(it) }

        actionExecutor.tryHandleMediaSearchSelection(userText)?.let { return toReply(it) }
        actionExecutor.tryHandleMediaSelection(userText)?.let { return toReply(it) }
        actionExecutor.tryHandleTransitSelection(userText)?.let { return toReply(it) }
        actionExecutor.tryHandleBookSelection(userText)?.let { return toReply(it) }

        return AssistantReply(
            "Sigo con ${actionExecutor.getPendingHint()}. " +
                "Di tu respuesta, repíteme las opciones, o cancela para empezar otra cosa.",
            skipAutoLearn = true,
        )
    }

    private suspend fun handleFreeConversation(userText: String): AssistantReply {
        if (contextIntentDetector.isRepeatOptionsRequest(userText)) {
            val prompt = repeatablePrompt()
            if (prompt.isNotBlank()) {
                return AssistantReply(prompt, skipAutoLearn = true)
            }
        }

        if (contextIntentDetector.detect(userText, hasPending = false) == ContextIntent.INTERRUPT) {
            return AssistantReply("", skipAutoLearn = true, actionTaken = true)
        }

        actionExecutor.tryHandleContactSelection(userText)?.let { return toReply(it) }

        actionExecutor.tryHandleTimeIntent(userText)?.let { return toReply(it) }
        actionExecutor.tryHandleCalculatorIntent(userText)?.let { return toReply(it) }
        actionExecutor.tryHandleWeatherIntent(userText)?.let { return toReply(it) }
        actionExecutor.tryHandleReceiptIntent(userText)?.let { return toReply(it) }
        actionExecutor.tryHandleNewsIntent(userText)?.let { return toReply(it) }

        actionExecutor.tryHandleWalkIntent(userText)?.let { return toReply(it) }

        actionExecutor.tryHandleRouteIntent(userText)?.let { return toReply(it) }

        actionExecutor.tryHandleSavedPlaceIntent(userText)?.let { return toReply(it) }

        actionExecutor.tryHandleNavigationIntent(userText)?.let { return toReply(it) }

        actionExecutor.tryHandleMediaSearchSelection(userText)?.let { return toReply(it) }
        actionExecutor.tryHandleMediaSelection(userText)?.let { return toReply(it) }

        actionExecutor.tryHandleTransitSelection(userText)?.let { return toReply(it) }

        actionExecutor.tryHandleBookSelection(userText)?.let { return toReply(it) }

        if (actionExecutor.isAffirmative(userText)) {
            return when (val result = actionExecutor.confirmPending()) {
                is ActionResult.Success -> successReply(result)
                is ActionResult.Error -> AssistantReply(result.message, skipAutoLearn = true)
                is ActionResult.NeedsConfirmation -> AssistantReply(result.prompt, skipAutoLearn = true)
            }
        }

        if (actionExecutor.isNegative(userText)) {
            return when (val result = actionExecutor.cancelPending()) {
                is ActionResult.Success -> AssistantReply(result.message, skipAutoLearn = true)
                is ActionResult.Error -> AssistantReply(result.message, skipAutoLearn = true)
                is ActionResult.NeedsConfirmation -> AssistantReply(result.prompt, skipAutoLearn = true)
            }
        }

        val skillMatch = skillMatcher.findBestMatch(userText)
        if (skillMatch != null) {
            val triggers = memoryRepository.parseTriggerPhrases(skillMatch.skill.triggerPhrases)
            val triggerLabel = triggers.firstOrNull() ?: skillMatch.skill.name
            if (skillMatch.score >= 0.9f) {
                return when (val result = skillExecutor.execute(skillMatch.skill)) {
                    is ActionResult.Success -> AssistantReply(
                        "${result.message} Como cuando dices \"$triggerLabel\".",
                        actionTaken = true,
                        suspendListening = result.suspendListening,
                    )
                    is ActionResult.Error -> AssistantReply(result.message)
                    is ActionResult.NeedsConfirmation -> {
                        recordPending(result.prompt, actionExecutor.getPendingHint())
                        AssistantReply(result.prompt)
                    }
                }
            }
            val prompt = "¿Te monto ${skillMatch.skill.name}? Di sí o no, sin prisa."
            actionExecutor.setPendingSkillExecution(skillMatch.skill, prompt)
            recordPending(prompt, "confirmar el skill")
            return AssistantReply(prompt)
        }

        actionExecutor.tryHandleMediaIntent(userText)?.let { return toReply(it) }

        actionExecutor.tryHandleBookIntent(userText)?.let { return toReply(it) }

        actionExecutor.tryHandleTransitIntent(userText)?.let { return toReply(it) }

        val generativeModel = model
            ?: return AssistantReply(
                "Falta configurar la clave de Gemini. Añade GEMINI_API_KEY en local.properties.",
            )

        val memoryContext = memoryContextBuilder.buildContextBlock()
        val history = conversationContext.formatRecentHistory()
        val enrichedPrompt = buildString {
            appendLine(memoryContext)
            if (history.isNotBlank()) {
                appendLine()
                appendLine(history)
            }
            appendLine()
            appendLine("=== MENSAJE ACTUAL ===")
            append("Mensaje del usuario: $userText")
        }

        return try {
            val response = generativeModel.generateContent(enrichedPrompt)
            val functionCall = response.functionCalls.firstOrNull()
            if (functionCall != null) {
                return handleFunctionCall(generativeModel, functionCall)
            }

            val reply = response.text?.trim().orEmpty().ifBlank { "Hecho." }
            AssistantReply(reply)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini error", e)
            AssistantReply(GeminiConfig.formatAssistantError(e))
        }
    }

    private suspend fun handleFunctionCall(
        generativeModel: GenerativeModel,
        functionCall: FunctionCallPart,
    ): AssistantReply {
        val args = functionCall.args.mapValues { it.value.toString().trim('"') }
        val actionResult = when (ToolName.fromId(functionCall.name)) {
            ToolName.WebSearch -> handleWebSearch(generativeModel, args["query"].orEmpty())
            else -> actionExecutor.execute(functionCall.name, args)
        }

        return when (actionResult) {
            is ActionResult.Success -> AssistantReply(
                actionResult.message,
                actionTaken = true,
                suspendListening = actionResult.suspendListening,
            )
            is ActionResult.NeedsConfirmation -> {
                recordPending(actionResult.prompt, actionExecutor.getPendingHint())
                AssistantReply(actionResult.prompt)
            }
            is ActionResult.Error -> AssistantReply(actionResult.message)
        }
    }

    private fun successReply(result: ActionResult.Success): AssistantReply {
        return AssistantReply(
            result.message,
            actionTaken = true,
            skipAutoLearn = true,
            suspendListening = result.suspendListening,
        )
    }

    private fun toReply(result: ActionResult): AssistantReply {
        return when (result) {
            is ActionResult.Success -> AssistantReply(
                result.message,
                actionTaken = true,
                skipAutoLearn = true,
                suspendListening = result.suspendListening,
            )
            is ActionResult.Error -> AssistantReply(result.message, skipAutoLearn = true)
            is ActionResult.NeedsConfirmation -> {
                recordPending(result.prompt, actionExecutor.getPendingHint())
                AssistantReply(result.prompt, skipAutoLearn = true)
            }
        }
    }

    private fun recordPending(prompt: String, hint: String) {
        conversationContext.recordPending(hint, prompt)
    }

    companion object {
        private const val TAG = "GeminiOrchestrator"
    }

    private suspend fun handleWebSearch(model: GenerativeModel, query: String): ActionResult {
        if (query.isBlank()) {
            return ActionResult.Error("No he entendido qué quieres buscar.")
        }
        return try {
            val searchResponse = model.generateContent(
                """
                ${SystemPrompt.PERSONALITY_HINT}
                Responde en máximo 3 frases cortas para leer en voz alta.
                Consulta: $query
                """.trimIndent(),
            )
            ActionResult.Success(
                searchResponse.text?.trim().orEmpty().ifBlank { "No encontré información sobre eso." },
            )
        } catch (e: Exception) {
            ActionResult.Error("No pude buscar en internet: ${e.localizedMessage ?: "error"}.")
        }
    }
}

private val GenerateContentResponse.text: String?
    get() = candidates.firstOrNull()?.content?.parts
        ?.filterIsInstance<com.google.ai.client.generativeai.type.TextPart>()
        ?.joinToString(" ") { it.text }
        ?.trim()

private val GenerateContentResponse.functionCalls: List<FunctionCallPart>
    get() = candidates.firstOrNull()?.content?.parts
        ?.filterIsInstance<FunctionCallPart>()
        ?: emptyList()
