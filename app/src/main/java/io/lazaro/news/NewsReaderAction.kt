package io.lazaro.news

import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import io.lazaro.BuildConfig
import io.lazaro.actions.ActionResult
import io.lazaro.ai.GeminiConfig
import io.lazaro.ai.SystemPrompt
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NewsReaderAction @Inject constructor(
    private val newsIntentDetector: NewsIntentDetector,
) {
    private val model: GenerativeModel? by lazy {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank()) return@lazy null
        GeminiConfig.createModel(apiKey = apiKey)
    }

    suspend fun tryPrepare(userText: String): ActionResult? {
        if (!newsIntentDetector.detect(userText)) return null
        return readSpanishHeadlines()
    }

    suspend fun readSpanishHeadlines(): ActionResult {
        val generativeModel = model
            ?: return ActionResult.Error("Falta la clave de Gemini para leer noticias.")

        return try {
            val response = generativeModel.generateContent(
                content {
                    text(
                        """
                        ${SystemPrompt.PERSONALITY_HINT}
                        El usuario es ciego y quiere escuchar titulares, no abrir apps.
                        Busca y resume los 5 titulares mas importantes de España HOY.
                        Reglas:
                        - Empieza con: "Titulares de hoy en España:"
                        - Una frase corta por titular, numeradas del uno al cinco.
                        - Solo hechos; sin URLs ni referencias visuales.
                        - Maximo 8 frases en total, listas para leer en voz alta.
                        """.trimIndent(),
                    )
                },
            )
            val spoken = response.candidates.firstOrNull()?.content?.parts
                ?.filterIsInstance<com.google.ai.client.generativeai.type.TextPart>()
                ?.joinToString(" ") { it.text }
                ?.trim()
                .orEmpty()
            if (spoken.isBlank()) {
                ActionResult.Error("No pude obtener titulares ahora. Intentalo en un rato.")
            } else {
                ActionResult.Success(spoken)
            }
        } catch (e: Exception) {
            Log.e(TAG, "News read error", e)
            ActionResult.Error("No pude leer las noticias: ${e.localizedMessage ?: "error"}.")
        }
    }

    companion object {
        private const val TAG = "NewsReaderAction"
    }
}
