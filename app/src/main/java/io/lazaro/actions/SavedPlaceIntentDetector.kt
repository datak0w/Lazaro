package io.lazaro.actions

import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

enum class SavedPlaceIntent {
    SAVE,
    LIST,
    DELETE,
}

@Singleton
class SavedPlaceIntentDetector @Inject constructor() {

    fun detect(userText: String): SavedPlaceIntent? {
        val text = normalize(userText)
        if (text.isBlank()) return null
        return when {
            DELETE_TRIGGERS.any { text.contains(it) } -> SavedPlaceIntent.DELETE
            LIST_TRIGGERS.any { text.contains(it) } -> SavedPlaceIntent.LIST
            SAVE_TRIGGERS.any { text.contains(it) } -> SavedPlaceIntent.SAVE
            else -> null
        }
    }

    fun extractPlaceName(userText: String): String? {
        val text = normalize(userText)
        val patterns = listOf(
            Regex("""guarda(?:r)?\s+(?:la\s+)?(?:posicion|posición|sitio|lugar|ubicacion|ubicación)\s+(?:como\s+)?(.+)"""),
            Regex("""guarda(?:r)?\s+(?:este\s+)?(?:sitio|lugar|punto)\s+(?:como\s+)?(.+)"""),
            Regex("""marca(?:r)?\s+(?:este\s+)?(?:sitio|lugar|punto)\s+(?:como\s+)?(.+)"""),
            Regex("""borra(?:r)?\s+(?:el\s+)?(?:sitio|lugar)\s+(.+)"""),
            Regex("""elimina(?:r)?\s+(?:el\s+)?(?:sitio|lugar)\s+(.+)"""),
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val name = match.groupValues[1].trim()
            if (name.isNotBlank() && name !in STOP_WORDS) return name
        }
        return null
    }

    private fun normalize(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace("lazaro", " ")
            .replace("lázaro", " ")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        private val STOP_WORDS = setOf("aqui", "aquí", "ahora", "actual", "este", "esta")

        private val SAVE_TRIGGERS = listOf(
            "guarda posicion", "guarda posición", "guardar posicion", "guardar posición",
            "guarda sitio", "guardar sitio", "guarda lugar", "guardar lugar",
            "guarda este sitio", "guarda este lugar", "guarda ubicacion", "guarda ubicación",
            "marca sitio", "marca este sitio", "marca lugar", "marca este lugar",
            "guarda punto", "guardar punto",
        )

        private val LIST_TRIGGERS = listOf(
            "mis sitios", "mis lugares", "sitios guardados", "lugares guardados",
            "puntos guardados", "mis puntos favoritos", "lugares favoritos",
            "que sitios tengo", "qué sitios tengo", "lista de sitios",
        )

        private val DELETE_TRIGGERS = listOf(
            "borra sitio", "borrar sitio", "elimina sitio", "eliminar sitio",
            "borra lugar", "borrar lugar", "elimina lugar", "eliminar lugar",
        )
    }
}
