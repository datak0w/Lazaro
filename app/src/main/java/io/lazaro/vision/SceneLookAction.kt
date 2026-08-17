package io.lazaro.vision

import android.graphics.Bitmap
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import io.lazaro.BuildConfig
import io.lazaro.actions.ActionResult
import io.lazaro.actions.LocationAction
import io.lazaro.ai.GeminiConfig
import io.lazaro.ai.SystemPrompt
import io.lazaro.memory.SavedPlaceRepository
import io.lazaro.navigation.NavigationBearing
import io.lazaro.navigation.NavigationAudioCoordinator
import io.lazaro.navigation.OwnNavigationGuide
import io.lazaro.pathguide.ObstacleLabeler
import io.lazaro.pathguide.PathGuideController
import io.lazaro.pathguide.RearCameraAnalyzer
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SceneLookAction @Inject constructor(
    private val intentDetector: SceneLookIntentDetector,
    private val rearCameraAnalyzer: RearCameraAnalyzer,
    private val pathGuideController: PathGuideController,
    private val obstacleLabeler: ObstacleLabeler,
    private val locationAction: LocationAction,
    private val savedPlaceRepository: SavedPlaceRepository,
    private val ownNavigationGuide: OwnNavigationGuide,
    private val navigationAudioCoordinator: NavigationAudioCoordinator,
) {
    private val model: GenerativeModel? by lazy {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank()) return@lazy null
        GeminiConfig.createModel(apiKey = apiKey)
    }

    suspend fun tryPrepare(userText: String): ActionResult? {
        if (!intentDetector.detect(userText)) return null
        return describeWhatIsAhead(SceneLookTrigger.USER_ASKED)
    }

    suspend fun describeWhatIsAhead(
        trigger: SceneLookTrigger = SceneLookTrigger.USER_ASKED,
    ): ActionResult {
        val generativeModel = model
            ?: return ActionResult.Error("Falta la clave de Gemini para describir la escena.")

        // Foto primero (camino crítico). Sitios cercanos en paralelo, sin bloquear Gemini.
        return coroutineScope {
            val nearbyDeferred = async { nearbySavedPlacesLine() }

            val bitmap = capturePhoto()
                ?: return@coroutineScope ActionResult.Error(
                    "No pude sacar la foto. Comprueba el permiso de cámara y apunta el teléfono hacia delante.",
                )

            try {
                val mlLabels = try {
                    obstacleLabeler.analyzeScene(bitmap)
                } catch (_: Exception) {
                    null
                }
                val sensorHints = pathGuideController.sensorVisionHints()
                val navLine = navigationContextLine()
                val mapsLine = navigationAudioCoordinator.lastMapsInstruction.value

                val maxSentences = when (trigger) {
                    SceneLookTrigger.USER_ASKED -> 3
                    SceneLookTrigger.NAV_START,
                    SceneLookTrigger.USER_STOPPED,
                    SceneLookTrigger.NAV_PERIODIC,
                    -> 2
                }
                val leadIn = when (trigger) {
                    SceneLookTrigger.NAV_START ->
                        "Acaba de empezar a caminar. Oriéntalo respecto a la ruta y al destino."
                    SceneLookTrigger.USER_STOPPED ->
                        "El usuario se ha parado. Dile qué hacer para reorientarse y seguir la ruta."
                    SceneLookTrigger.NAV_PERIODIC ->
                        "Comprobación periódica mientras camina. Avisa si debe girar, cambiar de acera o esquivar algo."
                    SceneLookTrigger.USER_ASKED ->
                        "El usuario pide qué hay delante ahora."
                }

                val bearing = ownNavigationGuide.bearingHintDegrees()
                val streetPref = ownNavigationGuide.currentStreetCached()
                val bearingLine = bearing?.let {
                    "Rumbo deseado de la ruta aprox. ${it.toInt()} grados (0=norte)."
                }
                val prefLine = streetPref?.let { "Calle en contexto: $it." }

                val prompt = buildString {
                    appendLine(SystemPrompt.PERSONALITY_HINT)
                    appendLine(leadIn)
                    appendLine(
                        """
                        Usuario CIEGO. Describe SOLO lo que se ve delante, para caminar.
                        Prioridad:
                        1) Alineación con la ruta (gira izq/der, sigue recto) respecto al rumbo/destino.
                        2) Peligro inmediato (bordillo, escalón, poste, coche, persona, obra).
                        3) Acera vs calzada / zona peatonal.
                        4) Referencia fija útil (fachada, cruce, farola).
                        Reglas:
                        - Máximo $maxSentences frases cortas, español de España, sin markdown.
                        - Usa verbos de acción: gira, sigue, cuidado, cruza.
                        - Sin «en la imagen»; habla directo.
                        - Prohibido relleno; si no hay peligro, di cómo alinearse con la ruta.
                        """.trimIndent(),
                    )
                    if (!mapsLine.isNullOrBlank()) {
                        appendLine()
                        appendLine("Última indicación de ruta: $mapsLine")
                    }
                    if (!sensorHints.isNullOrBlank()) {
                        appendLine()
                        appendLine("Pistas del sensor del teléfono: $sensorHints")
                    }
                    if (!bearingLine.isNullOrBlank()) {
                        appendLine()
                        appendLine(bearingLine)
                    }
                    if (!prefLine.isNullOrBlank()) {
                        appendLine()
                        appendLine(prefLine)
                    }
                    mlLabels?.items?.takeIf { it.isNotEmpty() }?.let { items ->
                        appendLine()
                        appendLine(
                            "Etiquetas ML locales (pueden fallar): " +
                                items.joinToString(", ") { it.spanish },
                        )
                    }
                    if (!navLine.isNullOrBlank()) {
                        appendLine()
                        appendLine("Contexto de ruta: $navLine")
                    }
                }

                val response = generativeModel.generateContent(
                    content {
                        image(bitmap)
                        text(prompt)
                    },
                )
                val spoken = response.text?.trim().orEmpty().ifBlank {
                    response.candidates.firstOrNull()?.content?.parts
                        ?.filterIsInstance<com.google.ai.client.generativeai.type.TextPart>()
                        ?.joinToString(" ") { it.text }
                        ?.trim()
                        .orEmpty()
                }

                if (spoken.isBlank()) {
                    ActionResult.Error("No pude describir la escena. Inténtalo otra vez con más luz.")
                } else {
                    val nearbyLine = withTimeoutOrNull(NEARBY_WAIT_MS) { nearbyDeferred.await() }
                    val prefix = when (trigger) {
                        SceneLookTrigger.USER_STOPPED -> "Te has parado. "
                        SceneLookTrigger.NAV_START -> "Para orientarte: "
                        SceneLookTrigger.NAV_PERIODIC -> ""
                        SceneLookTrigger.USER_ASKED -> ""
                    }
                    val full = buildString {
                        append(prefix)
                        append(spoken.trimEnd('.', ' ', '\n'))
                        append('.')
                        if (!nearbyLine.isNullOrBlank()) {
                            append(' ')
                            append(nearbyLine)
                        }
                    }
                    ActionResult.Success(full)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Scene look error", e)
                ActionResult.Error(GeminiConfig.formatAssistantError(e))
            } finally {
                if (!bitmap.isRecycled) {
                    try {
                        bitmap.recycle()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private suspend fun capturePhoto(): Bitmap? {
        val alreadyRunning = rearCameraAnalyzer.isRunning()
        if (!alreadyRunning) {
            if (!rearCameraAnalyzer.start()) return null
            delay(CAMERA_WARMUP_MS)
        }
        val color = rearCameraAnalyzer.captureColorBitmapSnapshot(timeoutMs = 2_000L)
        if (color != null) {
            if (!alreadyRunning && !pathGuideController.isActive()) {
                rearCameraAnalyzer.stop()
            }
            return color
        }
        val gray = rearCameraAnalyzer.captureBitmapSnapshot(timeoutMs = 1_500L)
        if (!alreadyRunning && !pathGuideController.isActive()) {
            rearCameraAnalyzer.stop()
        }
        return gray
    }

    private suspend fun nearbySavedPlacesLine(): String? {
        val loc = locationAction.getCurrentLocation() ?: return null
        val nearby = savedPlaceRepository.findNearby(
            loc.latitude,
            loc.longitude,
            NEARBY_RADIUS_M,
        )
        if (nearby.isEmpty()) return null
        val top = nearby.take(2)
        val parts = top.map { place ->
            val d = NavigationBearing.distanceMeters(
                loc.latitude, loc.longitude, place.latitude, place.longitude,
            ).toInt().coerceAtLeast(5)
            val rounded = if (d <= 100) d else (d / 10) * 10
            "a unos $rounded metros de ${place.displayName}"
        }
        return when (parts.size) {
            1 -> "Estás cerca de un sitio guardado: ${parts[0]}."
            else -> "Sitios guardados cerca: ${parts.joinToString("; ")}."
        }
    }

    private fun navigationContextLine(): String? {
        if (!navigationAudioCoordinator.isNavigationActive() && !ownNavigationGuide.hasRoute()) {
            return null
        }
        return ownNavigationGuide.cachedContextHint()
    }

    companion object {
        private const val TAG = "SceneLookAction"
        private const val NEARBY_RADIUS_M = 120.0
        private const val CAMERA_WARMUP_MS = 350L
        /** No retrasar la respuesta de visión esperando GPS de sitios. */
        private const val NEARBY_WAIT_MS = 800L
    }
}
