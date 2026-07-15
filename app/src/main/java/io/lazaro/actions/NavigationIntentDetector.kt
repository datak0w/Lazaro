package io.lazaro.actions

import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NavigationIntentDetector @Inject constructor() {

    fun detectDestination(userText: String): String? {
        val text = normalize(userText)
        if (text.isBlank()) return null
        if (!matchesAny(text, NAVIGATION_TRIGGERS)) return null

        for (pattern in DESTINATION_PATTERNS) {
            val match = pattern.find(text) ?: continue
            val destination = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (destination.isNotBlank() && destination.length >= 3) {
                return destination
            }
        }
        return null
    }

    private fun matchesAny(text: String, triggers: List<String>): Boolean {
        return triggers.any { text.contains(it) }
    }

    private fun normalize(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        private val NAVIGATION_TRIGGERS = listOf(
            "llevame", "llevarme", "guíame", "guiame", "navega", "navegar",
            "ir a", "ir hasta", "ir para", "ir hacia", "vamos a", "vamonos a",
            "como llego a", "como voy a", "ruta a", "maps a", "mapa a",
            "abre maps", "abre mapas", "google maps",
        )

        private val DESTINATION_PATTERNS = listOf(
            Regex("""(?:llevame|llevarme|guíame|guiame|navega(?:r)?) (?:a|hasta|hacia|para) (.+)"""),
            Regex("""(?:ir|vamos|vamonos) (?:a|hasta|hacia|para) (.+)"""),
            Regex("""(?:como (?:llego|voy)) (?:a|hasta|hacia|para) (.+)"""),
            Regex("""(?:ruta|maps|mapas|google maps) (?:a|hasta|hacia|para) (.+)"""),
            Regex("""(?:abre|abrir) (?:google )?maps? (?:a|hasta|hacia|para) (.+)"""),
        )
    }
}
