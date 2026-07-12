package io.lazaro.contacts

import android.content.Context
import android.provider.ContactsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import io.lazaro.memory.MemoryRepository
import io.lazaro.memory.entity.MemoryCategory
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val memoryRepository: MemoryRepository,
) {
    suspend fun findContacts(query: String): List<ContactMatch> {
        if (query.isBlank()) return emptyList()

        val results = linkedMapOf<String, ContactMatch>()

        addMemoryMatches(query, results)
        addDeviceContactMatches(query, results)

        return results.values.sortedBy { it.displayName.lowercase() }
    }

    suspend fun findSingleOrNull(query: String): ContactMatch? {
        val matches = findContacts(query)
        return when {
            matches.isEmpty() -> null
            matches.size == 1 -> matches.first()
            else -> matches.find { it.displayName.equals(query, ignoreCase = true) }
                ?: matches.find { it.displayName.contains(query, ignoreCase = true) && query.length >= 3 }
        }
    }

    fun normalizePhone(phone: String): String {
        return phone.filter { it.isDigit() || it == '+' }
    }

    fun formatPhoneForSpeech(phone: String): String {
        return normalizePhone(phone).map { it.toString() }.joinToString(" ")
    }

    private suspend fun addMemoryMatches(
        query: String,
        results: LinkedHashMap<String, ContactMatch>,
    ) {
        val memories = memoryRepository.getAllMemories()
            .filter { it.category == MemoryCategory.CONTACT || it.key.contains("phone", ignoreCase = true) }

        for (entry in memories) {
            val names = buildList {
                add(entry.key.replace("_", " "))
                addAll(entry.aliases.split("|").filter { it.isNotBlank() })
            }
            if (names.any { matchesQuery(query, it) } || matchesQuery(query, entry.value)) {
                val phone = normalizePhone(entry.value)
                if (phone.length >= 9) {
                    results[phone] = ContactMatch(
                        displayName = names.firstOrNull()?.replaceFirstChar { it.uppercase() } ?: entry.key,
                        phoneNumber = phone,
                        source = "memoria",
                    )
                }
            }
        }
    }

    private fun addDeviceContactMatches(
        query: String,
        results: LinkedHashMap<String, ContactMatch>,
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
                    if (phone.length < 9) continue
                    if (matchesQuery(query, name)) {
                        results[phone] = ContactMatch(name, phone, "contactos")
                    }
                }
            }
        } catch (_: SecurityException) {
            // READ_CONTACTS not granted
        }
    }

    private fun matchesQuery(query: String, target: String): Boolean {
        val q = query.lowercase().trim()
        val t = target.lowercase().trim()
        if (q.isBlank() || t.isBlank()) return false
        return t.contains(q) || q.contains(t) || t.split(" ").any { word ->
            word.startsWith(q) || q.startsWith(word)
        }
    }
}
