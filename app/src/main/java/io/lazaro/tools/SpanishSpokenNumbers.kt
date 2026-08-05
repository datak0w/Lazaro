package io.lazaro.tools

import java.text.Normalizer

/**
 * Convierte números en español hablado a dígitos dentro de una frase.
 * Ej.: «dieciocho por treinta y siete» → «18 por 37»
 *
 * «y» solo une decenas+unidades (treinta y siete → 37), no sumas (cinco y tres).
 */
object SpanishSpokenNumbers {

    fun replaceSpokenNumbers(text: String): String {
        val n = normalize(text)
        if (n.isBlank()) return n

        // 1) Decenas + y + unidades (30–99)
        var out = TENS_AND_UNITS.replace(n) { m ->
            val tens = WORD_VALUES[m.groupValues[1]] ?: return@replace m.value
            val units = WORD_VALUES[m.groupValues[2]] ?: return@replace m.value
            (tens + units).toString()
        }

        // 2) Cientos + resto opcional (ciento dieciocho, doscientos, etc.)
        out = HUNDREDS.replace(out) { m ->
            val hundreds = WORD_VALUES[m.groupValues[1]] ?: return@replace m.value
            val restRaw = m.groupValues[2].trim()
            val rest = if (restRaw.isBlank()) {
                0
            } else {
                WORD_VALUES[restRaw]
                    ?: parseCompound(restRaw)?.toInt()
                    ?: return@replace m.value
            }
            (hundreds + rest).toString()
        }

        // 3) Palabras sueltas / veinti*
        out = SINGLE.replace(out) { m ->
            val v = WORD_VALUES[m.value] ?: return@replace m.value
            v.toString()
        }

        return out.replace(Regex("""\s+"""), " ").trim()
    }

    private fun parseCompound(raw: String): Double? {
        TENS_AND_UNITS.matchEntire(raw)?.let { m ->
            val tens = WORD_VALUES[m.groupValues[1]] ?: return null
            val units = WORD_VALUES[m.groupValues[2]] ?: return null
            return (tens + units).toDouble()
        }
        return WORD_VALUES[raw]?.toDouble()
    }

    private fun normalize(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace("veintiun", "veintiuno")
            .replace("veintiuna", "veintiuno")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private val TENS_AND_UNITS = Regex(
        """\b(treinta|cuarenta|cincuenta|sesenta|setenta|ochenta|noventa)\s+y\s+""" +
            """(uno|una|un|dos|tres|cuatro|cinco|seis|siete|ocho|nueve)\b""",
    )

    private val HUNDREDS = Regex(
        """\b(cien|ciento|doscientos|trescientos|cuatrocientos|quinientos|""" +
            """seiscientos|setecientos|ochocientos|novecientos)\b""" +
            """(?:\s+((?:treinta|cuarenta|cincuenta|sesenta|setenta|ochenta|noventa)""" +
            """(?:\s+y\s+(?:uno|una|un|dos|tres|cuatro|cinco|seis|siete|ocho|nueve))?|""" +
            """veintiuno|veintidos|veintitres|veinticuatro|veinticinco|veintiseis|""" +
            """veintisiete|veintiocho|veintinueve|veinte|""" +
            """diez|once|doce|trece|catorce|quince|dieciseis|diecisiete|dieciocho|diecinueve|""" +
            """uno|una|un|dos|tres|cuatro|cinco|seis|siete|ocho|nueve))?""",
    )

    private val SINGLE = Regex(
        """\b(?:cero|uno|una|un|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|""" +
            """diez|once|doce|trece|catorce|quince|dieciseis|diecisiete|dieciocho|diecinueve|""" +
            """veinte|veintiuno|veintidos|veintitres|veinticuatro|veinticinco|veintiseis|""" +
            """veintisiete|veintiocho|veintinueve|""" +
            """treinta|cuarenta|cincuenta|sesenta|setenta|ochenta|noventa|mil)\b""",
    )

    private val WORD_VALUES = mapOf(
        "cero" to 0,
        "un" to 1, "una" to 1, "uno" to 1,
        "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5,
        "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9,
        "diez" to 10, "once" to 11, "doce" to 12, "trece" to 13,
        "catorce" to 14, "quince" to 15, "dieciseis" to 16, "diecisiete" to 17,
        "dieciocho" to 18, "diecinueve" to 19,
        "veinte" to 20, "veintiuno" to 21, "veintidos" to 22, "veintitres" to 23,
        "veinticuatro" to 24, "veinticinco" to 25, "veintiseis" to 26,
        "veintisiete" to 27, "veintiocho" to 28, "veintinueve" to 29,
        "treinta" to 30, "cuarenta" to 40, "cincuenta" to 50,
        "sesenta" to 60, "setenta" to 70, "ochenta" to 80, "noventa" to 90,
        "cien" to 100, "ciento" to 100,
        "doscientos" to 200, "trescientos" to 300, "cuatrocientos" to 400,
        "quinientos" to 500, "seiscientos" to 600, "setecientos" to 700,
        "ochocientos" to 800, "novecientos" to 900,
        "mil" to 1000,
    )
}
