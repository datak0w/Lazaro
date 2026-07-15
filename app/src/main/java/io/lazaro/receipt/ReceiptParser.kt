package io.lazaro.receipt

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import kotlin.math.abs

object ReceiptParser {

    fun analyze(ocrText: String): ReceiptAnalysis {
        val lines = ocrText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val parsedLines = mutableListOf<ReceiptLine>()
        var declaredTotal: BigDecimal? = null
        var declaredSubtotal: BigDecimal? = null

        for (line in lines) {
            val normalized = normalize(line)
            val amount = extractTrailingAmount(line) ?: continue

            when {
                TOTAL_KEYWORDS.any { normalized.contains(it) } -> {
                    if (normalized.contains("subtotal") || normalized.contains("base imponible")) {
                        declaredSubtotal = amount
                    } else {
                        declaredTotal = amount
                    }
                }
                IVA_KEYWORDS.any { normalized.contains(it) } -> Unit
                else -> parsedLines += ReceiptLine(description = line, amount = amount)
            }
        }

        val itemSum = parsedLines.fold(BigDecimal.ZERO) { acc, item -> acc + item.amount }
        val referenceTotal = declaredTotal ?: declaredSubtotal
        val discrepancy = if (referenceTotal != null) {
            itemSum - referenceTotal
        } else {
            null
        }

        val match = discrepancy?.let { abs(it.toDouble()) < 0.05 } ?: false
        val spoken = buildSpeech(parsedLines, referenceTotal, itemSum, discrepancy, match)

        return ReceiptAnalysis(
            lines = parsedLines,
            computedSum = itemSum,
            declaredTotal = referenceTotal,
            discrepancy = discrepancy,
            totalsMatch = match,
            spokenSummary = spoken,
        )
    }

    private fun buildSpeech(
        lines: List<ReceiptLine>,
        declaredTotal: BigDecimal?,
        itemSum: BigDecimal,
        discrepancy: BigDecimal?,
        match: Boolean,
    ): String {
        if (lines.isEmpty() && declaredTotal == null) {
            return "No he podido leer cantidades en el ticket. Acércalo más y con buena luz."
        }

        val parts = mutableListOf<String>()
        parts += "He leído ${lines.size} líneas con precio."

        if (lines.isNotEmpty()) {
            val preview = lines.take(4).joinToString(". ") { line ->
                "${line.description}: ${formatMoney(line.amount)}"
            }
            parts += preview
        }

        parts += "La suma de las líneas es ${formatMoney(itemSum)}."

        if (declaredTotal != null) {
            parts += "El total del ticket marca ${formatMoney(declaredTotal)}."
            if (match) {
                parts += "Cuadra. No parece que te hayan timado."
            } else if (discrepancy != null) {
                val diff = formatMoney(discrepancy.abs())
                if (discrepancy > BigDecimal.ZERO) {
                    parts += "Ojo: las líneas suman $diff más que el total impreso. Revisa el ticket."
                } else {
                    parts += "Ojo: el total impreso es $diff mayor que la suma de líneas. Revisa el ticket."
                }
            }
        } else {
            parts += "No vi un total claro impreso. La suma calculada es ${formatMoney(itemSum)}."
        }

        return parts.joinToString(" ")
    }

    private fun extractTrailingAmount(line: String): BigDecimal? {
        val patterns = listOf(
            Regex("""(\d{1,4}[.,]\d{2})\s*€"""),
            Regex("""€\s*(\d{1,4}[.,]\d{2})"""),
            Regex("""(\d{1,4}[.,]\d{2})\s*(?:eur)?$""", RegexOption.IGNORE_CASE),
        )
        for (pattern in patterns) {
            val match = pattern.find(line) ?: continue
            return parseMoney(match.groupValues[1]) ?: continue
        }
        return null
    }

    private fun parseMoney(raw: String): BigDecimal? {
        val normalized = raw.replace(',', '.')
        return runCatching {
            BigDecimal(normalized).setScale(2, RoundingMode.HALF_UP)
        }.getOrNull()
    }

    private fun formatMoney(value: BigDecimal): String {
        return "${value.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',')} euros"
    }

    private fun normalize(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .trim()
    }

    private val TOTAL_KEYWORDS = listOf(
        "total", "importe", "a pagar", "amount due", "suma",
    )

    private val IVA_KEYWORDS = listOf(
        "iva", "i.v.a", "impuesto", "tax",
    )
}

data class ReceiptLine(
    val description: String,
    val amount: BigDecimal,
)

data class ReceiptAnalysis(
    val lines: List<ReceiptLine>,
    val computedSum: BigDecimal,
    val declaredTotal: BigDecimal?,
    val discrepancy: BigDecimal?,
    val totalsMatch: Boolean,
    val spokenSummary: String,
)
