package io.lazaro.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
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
            if (digits.filter { it.isDigit() }.length >= 9) {
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

    /**
     * Tras confirmar: marca sola, sin dejar al usuario ciego en el marcador.
     */
    fun executeCall(contact: ContactMatch): ActionResult {
        val phone = contactResolver.normalizePhone(contact.phoneNumber)
        if (phone.filter { it.isDigit() }.length < 3) {
            return ActionResult.Error("No tengo un número válido para llamar a ${contact.displayName}.")
        }
        if (!hasCallPermission()) {
            return ActionResult.Error(
                "Necesito permiso de llamadas para marcar solo. " +
                    "En ajustes de Lazaro, activa Teléfono o Llamadas, y vuelve a pedírmelo.",
            )
        }

        return try {
            placeOutgoingCall(phone)
            ActionResult.Success("Llamando a ${contact.displayName}.")
        } catch (e: SecurityException) {
            ActionResult.Error(
                "Sin permiso para llamar. Activa el permiso de teléfono para Lazaro en ajustes.",
            )
        } catch (e: Exception) {
            ActionResult.Error("No pude iniciar la llamada: ${e.message}")
        }
    }

    fun placeOutgoingCall(phone: String) {
        val uri = Uri.fromParts("tel", phone, null)
        val telecom = context.getSystemService(TelecomManager::class.java)
        if (telecom != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                telecom.placeCall(uri, Bundle())
                return
            } catch (_: Exception) {
                // Fallback ACTION_CALL
            }
        }
        context.startActivity(
            Intent(Intent.ACTION_CALL, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    fun prepareIncomingAnswer(displayName: String, phoneNumber: String): ActionResult {
        val label = displayName.ifBlank { "número desconocido" }
        return ActionResult.NeedsConfirmation(
            prompt = "LLAMADA. $label. Di responde, sí o cógelo.",
            pendingAction = PendingAction(
                toolName = TOOL_ANSWER_INCOMING,
                args = mapOf(
                    "contact_name" to label,
                    "phone_number" to phoneNumber,
                ),
            ),
        )
    }

    fun answerIncomingCall(): ActionResult {
        if (!hasAnswerPermission()) {
            return ActionResult.Error(
                "Necesito permiso para responder llamadas. Actívalo en ajustes de la app.",
            )
        }
        return try {
            val telecom = context.getSystemService(TelecomManager::class.java)
                ?: return ActionResult.Error("No puedo acceder al teléfono.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                telecom.acceptRingingCall()
            } else {
                return ActionResult.Error("Este Android no permite responder desde Lázaro.")
            }
            ActionResult.Success(
                "Respondiendo. Durante la llamada di Lázaro cuelga para colgar.",
            )
        } catch (e: SecurityException) {
            ActionResult.Error("Sin permiso para responder. Activa «Responder llamadas» para Lázaro.")
        } catch (e: Exception) {
            ActionResult.Error("No pude responder: ${e.message}")
        }
    }

    fun rejectIncomingCall(): ActionResult {
        if (!hasAnswerPermission()) {
            return ActionResult.Success("Vale, no respondo. Recházala en el teléfono si quieres.")
        }
        return try {
            val telecom = context.getSystemService(TelecomManager::class.java)
                ?: return ActionResult.Success("Vale, no respondo.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                telecom.endCall()
            }
            ActionResult.Success("Rechazada.")
        } catch (_: SecurityException) {
            ActionResult.Success("Vale, no respondo.")
        } catch (_: Exception) {
            ActionResult.Success("Vale, no respondo.")
        }
    }

    /** Cuelga la llamada activa (o rechaza si aún suena). */
    fun hangUpActiveCall(): ActionResult {
        if (!hasAnswerPermission()) {
            return ActionResult.Error(
                "Necesito permiso para colgar. Activa «Responder llamadas» para Lázaro en ajustes.",
            )
        }
        return try {
            val telecom = context.getSystemService(TelecomManager::class.java)
                ?: return ActionResult.Error("No puedo acceder al teléfono.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val ended = telecom.endCall()
                if (ended) ActionResult.Success("Llamada colgada.")
                else ActionResult.Success("He pedido colgar la llamada.")
            } else {
                ActionResult.Error("Este Android no permite colgar desde Lázaro.")
            }
        } catch (e: SecurityException) {
            ActionResult.Error("Sin permiso para colgar. Activa el permiso de teléfono para Lázaro.")
        } catch (e: Exception) {
            ActionResult.Error("No pude colgar: ${e.message}")
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

    private fun hasCallPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasAnswerPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ANSWER_PHONE_CALLS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val TOOL_ANSWER_INCOMING = "answer_incoming_call"
    }
}
