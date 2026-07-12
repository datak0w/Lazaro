package io.lazaro.ai

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.Tool
import io.lazaro.BuildConfig

object GeminiConfig {
    /** Modelo por defecto compatible con la API actual (gemini-2.0-flash devuelve 404). */
    const val DEFAULT_MODEL = "gemini-3.5-flash"

    fun modelName(): String = BuildConfig.GEMINI_MODEL.ifBlank { DEFAULT_MODEL }

    fun createModel(
        apiKey: String,
        systemInstruction: Content? = null,
        tools: List<Tool>? = null,
    ): GenerativeModel {
        return GenerativeModel(
            modelName = modelName(),
            apiKey = apiKey,
            systemInstruction = systemInstruction,
            tools = tools,
        )
    }

    fun formatAssistantError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("404", ignoreCase = true) ||
                message.contains("NOT_FOUND", ignoreCase = true) ||
                message.contains("no longer available", ignoreCase = true) -> {
                "El modelo ${modelName()} no está disponible con tu clave API. " +
                    "En local.properties pon GEMINI_MODEL=gemini-3.5-flash y recompila."
            }
            message.contains("function call turn", ignoreCase = true) ||
                message.contains("thought_signature", ignoreCase = true) ||
                message.contains("INVALID_ARGUMENT", ignoreCase = true) -> {
                "No pude completar la acción con la IA. Inténtalo otra vez."
            }
            else -> "Hubo un problema con el asistente: ${error.localizedMessage ?: "error desconocido"}."
        }
    }
}
