package io.lazaro.navigation

import io.lazaro.actions.ActionResult
import io.lazaro.actions.PendingAction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * «En Calle Carretera ve siempre por la derecha»
 * «Recuerda: Calle X, acera izquierda / evita jardineras»
 */
@Singleton
class StreetSideIntentDetector @Inject constructor() {

    fun detect(userText: String): StreetSideIntent? {
        val t = userText.lowercase().trim()
        if (t.isBlank()) return null
        val streetPatterns = listOf(
            Regex(
                """(?:en|por)\s+(calle|avenida|avda\.?|camino|plaza|paseo)\s+([a-záéíóúñü0-9\s]+?)\s+""" +
                    """(?:ve\s+siempre\s+por|siempre\s+por|usa|prefer(?:e|ir)?)\s+(?:la\s+)?(?:acera\s+)?(?:de\s+la\s+)?(izquierda|derecha|izq|der)""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """(?:recuerda|guarda|apunta)[:\s]+(?:en\s+)?(calle|avenida|avda\.?|camino|plaza|paseo)\s+([a-záéíóúñü0-9\s]+?)[,\s]+""" +
                    """(?:acera\s+)?(?:de\s+la\s+)?(izquierda|derecha|izq|der)""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """(calle|avenida|avda\.?|camino|plaza|paseo)\s+([a-záéíóúñü0-9\s]+?)\s+""" +
                    """(?:siempre\s+)?(?:por\s+la\s+)?(?:acera\s+)?(?:de\s+la\s+)?(izquierda|derecha)""",
                RegexOption.IGNORE_CASE,
            ),
        )
        for (re in streetPatterns) {
            val m = re.find(t) ?: continue
            val type = m.groupValues.getOrNull(1).orEmpty()
            val name = m.groupValues.getOrNull(2)?.trim().orEmpty()
            val sideRaw = m.groupValues.getOrNull(3)?.lowercase().orEmpty()
            if (name.length < 2) continue
            val side = when {
                sideRaw.startsWith("izq") -> PreferredSidewalkSide.LEFT
                sideRaw.startsWith("der") -> PreferredSidewalkSide.RIGHT
                else -> continue
            }
            val street = "$type $name".trim()
            val reason = extractReason(t)
            return StreetSideIntent(street, side, reason)
        }
        return null
    }

    private fun extractReason(t: String): String? {
        val m = Regex(
            """(?:por|evita|evitar|debido a|a causa de)\s+([a-záéíóúñü\s]{3,40})""",
            RegexOption.IGNORE_CASE,
        ).find(t) ?: return null
        return m.groupValues[1].trim().take(60).ifBlank { null }
    }
}

data class StreetSideIntent(
    val streetName: String,
    val side: PreferredSidewalkSide,
    val reason: String?,
)

@Singleton
class StreetSideAction @Inject constructor(
    private val detector: StreetSideIntentDetector,
    private val repository: StreetSidePreferenceRepository,
) {
    suspend fun tryPrepare(userText: String): ActionResult? {
        val intent = detector.detect(userText) ?: return null
        val sideWord = when (intent.side) {
            PreferredSidewalkSide.LEFT -> "izquierda"
            PreferredSidewalkSide.RIGHT -> "derecha"
            PreferredSidewalkSide.EITHER -> "cualquiera"
        }
        val reasonBit = intent.reason?.let { " ($it)" }.orEmpty()
        val prompt =
            "¿Confirmas: en ${intent.streetName} siempre por la acera de la $sideWord$reasonBit?"
        return ActionResult.NeedsConfirmation(
            prompt = prompt,
            pendingAction = PendingAction(
                toolName = "save_street_side",
                args = mapOf(
                    "street" to intent.streetName,
                    "side" to intent.side.name,
                    "reason" to (intent.reason.orEmpty()),
                ),
            ),
        )
    }

    suspend fun confirmSave(args: Map<String, String>): ActionResult {
        val street = args["street"].orEmpty()
        val side = runCatching {
            PreferredSidewalkSide.valueOf(args["side"].orEmpty())
        }.getOrNull() ?: return ActionResult.Error("No entendí el lado de la acera.")
        if (street.isBlank()) return ActionResult.Error("No tengo el nombre de la calle.")
        val reason = args["reason"]?.ifBlank { null }
        val saved = repository.save(street, side, reason)
        val sideWord = when (saved.preferredSide) {
            PreferredSidewalkSide.LEFT -> "izquierda"
            PreferredSidewalkSide.RIGHT -> "derecha"
            PreferredSidewalkSide.EITHER -> "cualquiera"
        }
        return ActionResult.Success(
            "Guardado. En ${saved.streetKey} te guiaré por la acera de la $sideWord.",
        )
    }
}
