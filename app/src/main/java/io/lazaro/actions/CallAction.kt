package io.lazaro.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.lazaro.contacts.ContactMatch
import io.lazaro.contacts.ContactResolver
import io.lazaro.voice.VoiceOptionParser
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallAction @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactResolver: ContactResolver,
) {
    suspend fun prepareCall(contactQuery: String): ActionResult {
        if (contactQuery.isBlank()) {
            return ActionResult.Error("¿A quién quieres llamar?")
        }

        val matches = contactResolver.findContacts(contactQuery)
        if (matches.isEmpty()) {
            val digits = contactResolver.normalizePhone(contactQuery)
            if (digits.length >= 9) {
                return buildCallConfirmation(
                    ContactMatch(displayName = contactQuery, phoneNumber = digits, source = "número"),
                )
            }
            return ActionResult.Error("No encuentro a $contactQuery en tus contactos ni en memoria.")
        }

        if (matches.size == 1) {
            return buildCallConfirmation(matches.first())
        }

        val options = matches.take(5).mapIndexed { index, match ->
            "${index + 1}: ${match.displayName}"
        }.joinToString(". ")
        return ActionResult.NeedsConfirmation(
            prompt = "Encontré ${matches.size} contactos: $options. Di el número o el nombre completo.",
            pendingAction = PendingAction(
                toolName = "select_contact_call",
                args = matches.take(5).mapIndexed { index, m ->
                    "candidate_$index" to "${m.displayName}|${m.phoneNumber}"
                }.toMap() + ("query" to contactQuery),
            ),
        )
    }

    suspend fun resolveContactSelection(args: Map<String, String>, selection: String): ContactMatch? {
        val candidates = args.filterKeys { it.startsWith("candidate_") }
            .values
            .mapNotNull { encoded ->
                val parts = encoded.split("|", limit = 2)
                if (parts.size == 2) ContactMatch(parts[0], parts[1], "contactos") else null
            }

        val index = VoiceOptionParser.parseIndex(selection, candidates.size)
        if (index != null && index in candidates.indices) {
            return candidates[index]
        }

        return contactResolver.findSingleOrNull(selection)
            ?: contactResolver.findContacts(args["query"].orEmpty()).find {
                it.displayName.equals(selection, ignoreCase = true)
            }
    }

    fun executeCall(contact: ContactMatch): ActionResult {
        val phone = contactResolver.normalizePhone(contact.phoneNumber)
        val hasCallPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CALL_PHONE,
        ) == PackageManager.PERMISSION_GRANTED

        val intent = if (hasCallPermission) {
            Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        context.startActivity(intent)
        val spokenPhone = contactResolver.formatPhoneForSpeech(phone)
        return if (hasCallPermission) {
            ActionResult.Success("Llamando a ${contact.displayName}.")
        } else {
            ActionResult.Success(
                "Abro el marcador con ${contact.displayName}, número $spokenPhone. Confirma la llamada en el teléfono.",
            )
        }
    }

    fun requestCallConfirmation(contact: ContactMatch): ActionResult = buildCallConfirmation(contact)

    private fun buildCallConfirmation(contact: ContactMatch): ActionResult {
        val spokenPhone = contactResolver.formatPhoneForSpeech(contact.phoneNumber)
        return ActionResult.NeedsConfirmation(
            prompt = "¿Confirmas que quieres llamar a ${contact.displayName} al $spokenPhone?",
            pendingAction = PendingAction(
                toolName = ToolName.MakeCall.id,
                args = mapOf(
                    "contact_name" to contact.displayName,
                    "phone_number" to contact.phoneNumber,
                ),
            ),
        )
    }
}
