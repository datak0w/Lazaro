package io.lazaro.contacts

import android.content.Context
import android.provider.ContactsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import io.lazaro.memory.MemoryRepository
import io.lazaro.memory.entity.MemoryCategory
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.min

@Singleton
class ContactResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val memoryRepository: MemoryRepository,
) {
    suspend fun findContacts(query: String): List<ContactMatch> {
        return findScoredContacts(query).map { it.match }
    }

    /**
     * Busca y ordena por relevancia. Evita que «Poti» coincida con «Pedro P»
     * solo porque el apellido es la letra P.
     */
    suspend fun findScoredContacts(query: String): List<ScoredContactMatch> {
        if (query.isBlank()) return emptyList()
        val q = normalizeName(query)
        if (q.isBlank()) return emptyList()

        val bestByPhone = linkedMapOf<String, ScoredContactMatch>()

        fun consider(match: ContactMatch, nameForScore: String) {
            val score = matchScore(q, nameForScore)
            if (score < MIN_SCORE) return
            val phone = normalizePhone(match.phoneNumber)
            if (phone.length < 9 && match.source != "número") return
            val key = phone.ifBlank { match.displayName.lowercase() }
            val prev = bestByPhone[key]
            if (prev == null || score > prev.score) {
                bestByPhone[key] = ScoredContactMatch(match.copy(phoneNumber = phone.ifBlank { match.phoneNumber }), score)
            }
        }

        addMemoryMatches(q, ::consider)
        addDeviceContactMatches(q, ::consider)

        return bestByPhone.values.sortedWith(
            compareByDescending<ScoredContactMatch> { it.score }
                .thenBy { it.match.displayName.lowercase() },
        )
    }

    suspend fun findSingleOrNull(query: String): ContactMatch? {
        val scored = findScoredContacts(query)
        if (scored.isEmpty()) return null
        if (scored.size == 1) return scored.first().match
        val top = scored[0]
        val second = scored[1]
        // Un claro ganador frente al resto
        if (top.score >= second.score + CLEAR_WIN_MARGIN) return top.match
        return scored.find { normalizeName(it.match.displayName) == normalizeName(query) }?.match
    }

    fun normalizePhone(phone: String): String {
        return phone.filter { it.isDigit() || it == '+' }
    }

    /**
     * Dígitos internacionales para wa.me / api.whatsapp.com (sin +).
     * España: 9 dígitos que empiezan por 6/7 → antepone 34.
     */
    fun toWhatsAppPhoneDigits(phone: String): String {
        var digits = phone.filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        if (digits.startsWith("00") && digits.length > 4) {
            digits = digits.removePrefix("00")
        }
        if (digits.length == 9 && (digits.startsWith("6") || digits.startsWith("7"))) {
            digits = "34$digits"
        }
        return digits
    }

    fun formatPhoneForSpeech(phone: String): String {
        return normalizePhone(phone).map { it.toString() }.joinToString(" ")
    }

    /** Busca el nombre del contacto por número (últimos dígitos). */
    fun lookupByPhone(phoneNumber: String): ContactMatch? {
        val target = digitsOnly(phoneNumber)
        if (target.length < 6) return null

        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            )
            val cursor = context.contentResolver.query(uri, projection, null, null, null)
                ?: return null
            cursor.use {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val phoneIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                var best: ContactMatch? = null
                var bestScore = -1
                while (it.moveToNext()) {
                    val name = it.getString(nameIdx).orEmpty()
                    val phone = digitsOnly(it.getString(phoneIdx).orEmpty())
                    if (phone.length < 6 || name.isBlank()) continue
                    val score = phoneMatchScore(target, phone)
                    if (score > bestScore) {
                        bestScore = score
                        best = ContactMatch(name, phone, "contactos")
                    }
                }
                if (bestScore >= 6) return best
            }
        } catch (_: SecurityException) {
            // READ_CONTACTS not granted
        }
        return null
    }

    private fun digitsOnly(phone: String): String = phone.filter { it.isDigit() }

    private fun phoneMatchScore(a: String, b: String): Int {
        if (a == b) return 100
        val suffixLen = minOf(a.length, b.length, 9)
        if (suffixLen < 6) return -1
        val aSuffix = a.takeLast(suffixLen)
        val bSuffix = b.takeLast(suffixLen)
        return if (aSuffix == bSuffix) suffixLen else -1
    }

    private suspend fun addMemoryMatches(
        queryNormalized: String,
        consider: (ContactMatch, String) -> Unit,
    ) {
        val memories = memoryRepository.getAllMemories()
            .filter { it.category == MemoryCategory.CONTACT || it.key.contains("phone", ignoreCase = true) }

        for (entry in memories) {
            val names = buildList {
                add(entry.key.replace("_", " "))
                addAll(entry.aliases.split("|").filter { it.isNotBlank() })
            }
            val phone = normalizePhone(entry.value)
            if (phone.length < 9 && !queryNormalized.any { it.isDigit() }) {
                // Permitir score por nombre aunque el valor no sea teléfono limpio
            }
            val display = names.firstOrNull()?.replaceFirstChar { it.uppercase() } ?: entry.key
            val match = ContactMatch(
                displayName = display,
                phoneNumber = phone,
                source = "memoria",
            )
            for (name in names) {
                consider(match, name)
            }
            if (phone.isNotBlank()) {
                consider(match, entry.value)
            }
        }
    }

    private fun addDeviceContactMatches(
        queryNormalized: String,
        consider: (ContactMatch, String) -> Unit,
    ) {
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            )
            val cursor = context.contentResolver.query(uri, projection, null, null, null) ?: return

            cursor.use {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val phoneIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext()) {
                    val name = it.getString(nameIdx).orEmpty()
                    val phone = normalizePhone(it.getString(phoneIdx).orEmpty())
                    if (phone.length < 9 || name.isBlank()) continue
                    // queryNormalized solo se usa para short-circuit vacío (ya filtrado)
                    if (queryNormalized.isBlank()) continue
                    consider(ContactMatch(name, phone, "contactos"), name)
                }
            }
        } catch (_: SecurityException) {
            // READ_CONTACTS not granted
        }
    }

    companion object {
        const val MIN_SCORE = 55
        const val CLEAR_WIN_MARGIN = 15

        fun normalizeName(text: String): String {
            return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase()
                .replace(Regex("[^a-z0-9\\s+]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        /**
         * Puntuación de similitud nombre↔consulta.
         * Importante: no premiar iniciales de 1 letra («P» en «Pedro P»).
         */
        fun matchScore(queryNormalized: String, targetRaw: String): Int {
            val q = normalizeName(queryNormalized)
            val t = normalizeName(targetRaw)
            if (q.isBlank() || t.isBlank()) return 0
            if (q == t) return 100

            val tokens = t.split(' ').filter { it.isNotBlank() }
            // Exact token: exige ≥2 letras (si no, «p» = «Pedro P»).
            if (q.length >= 2 && tokens.any { it == q }) return 95

            // Prefijo de token: «poti» → «potito», no «p»
            if (q.length >= 3) {
                val prefixHits = tokens.filter { it.length >= 3 && it.startsWith(q) }
                if (prefixHits.isNotEmpty()) return 88
            }

            // El token es apodo corto del query: «ana» → consulta más larga
            if (q.length >= 3 && tokens.any { it.length >= 3 && q.startsWith(it) }) {
                return 72
            }

            if (q.length >= 3 && t.contains(q)) return 70

            // Fuzzy solo entre tokens de longitud similar (Poti / Potti)
            if (q.length >= 3) {
                var bestDist = Int.MAX_VALUE
                for (token in tokens) {
                    if (token.length < 3) continue
                    if (abs(token.length - q.length) > 2) continue
                    bestDist = min(bestDist, levenshtein(q, token))
                }
                when {
                    bestDist <= 1 -> return 82
                    bestDist == 2 && q.length >= 4 -> return 60
                }
            }

            // Iniciales completas: «pp» ≠ «poti»; «mp» para «María Pérez» solo si query son iniciales
            if (q.length in 2..3 && q.all { it.isLetter() } && tokens.size >= 2) {
                val initials = tokens.mapNotNull { it.firstOrNull()?.toString() }.joinToString("")
                if (initials == q) return 65
            }

            return 0
        }

        fun levenshtein(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            val prev = IntArray(b.length + 1) { it }
            val cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = min(
                        min(cur[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost,
                    )
                }
                for (j in prev.indices) prev[j] = cur[j]
            }
            return prev[b.length]
        }
    }
}

data class ScoredContactMatch(
    val match: ContactMatch,
    val score: Int,
)
