package io.lazaro.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.KeyEvent
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

        val scored = contactResolver.findScoredContacts(contactQuery)
        if (scored.isEmpty()) {
            val digits = contactResolver.normalizePhone(contactQuery)
            if (digits.filter { it.isDigit() }.length >= 9) {
                return buildCallConfirmation(
                    ContactMatch(displayName = contactQuery, phoneNumber = digits, source = "número"),
                )
            }
            return ActionResult.Error("No encuentro a $contactQuery en tus contactos ni en memoria.")
        }

        val top = scored.first()
        val second = scored.getOrNull(1)
        val clearWinner = second == null ||
            top.score >= second.score + ContactResolver.CLEAR_WIN_MARGIN

        if (scored.size == 1 || clearWinner) {
            return buildCallConfirmation(top.match)
        }

        val options = scored.take(5).mapIndexed { index, item ->
            "${index + 1}: ${item.match.displayName}"
        }.joinToString(". ")
        return ActionResult.NeedsConfirmation(
            prompt = "Encontré varias personas parecidas a $contactQuery: $options. Di el número o el nombre completo.",
            pendingAction = PendingAction(
                toolName = "select_contact_call",
                args = scored.take(5).mapIndexed { index, item ->
                    "candidate_$index" to "${item.match.displayName}|${item.match.phoneNumber}"
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
            ?: candidates.maxByOrNull {
                ContactResolver.matchScore(selection, it.displayName)
            }?.takeIf {
                ContactResolver.matchScore(selection, it.displayName) >= ContactResolver.MIN_SCORE
            }
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
            prompt = "LLAMADA. $label. ¿Respondo? Di sí o no.",
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
            val ok = acceptRingingWithFallbacks()
            if (!ok) {
                restoreNormalAudio()
                return ActionResult.Error(
                    "No pude coger la llamada. Prueba otra vez o cógela en el teléfono.",
                )
            }
            // No seguir escuchando: el micrófono es para la llamada.
            ActionResult.Success(
                "Respondiendo. Di Lázaro cuelga para colgar, o botón cancelar del bastón.",
                suspendListening = true,
            )
        } catch (e: SecurityException) {
            restoreNormalAudio()
            ActionResult.Error("Sin permiso para responder. Activa «Responder llamadas» para Lázaro.")
        } catch (e: Exception) {
            restoreNormalAudio()
            ActionResult.Error("No pude responder: ${e.message}")
        }
    }

    fun rejectIncomingCall(): ActionResult {
        return try {
            val ok = endCallWithFallbacks()
            restoreNormalAudio()
            if (ok) ActionResult.Success("Llamada rechazada.")
            else ActionResult.Success("Vale, no respondo. Recházala en el teléfono si sigue sonando.")
        } catch (_: SecurityException) {
            restoreNormalAudio()
            ActionResult.Success("Vale, no respondo. Recházala en el teléfono.")
        } catch (_: Exception) {
            restoreNormalAudio()
            ActionResult.Success("Vale, no respondo.")
        }
    }

    /** Cuelga la llamada activa o rechaza si aún suena. */
    fun hangUpActiveCall(): ActionResult {
        if (!hasAnswerPermission() && !hasCallPermission()) {
            return if (telephonyCallState() != TelephonyManager.CALL_STATE_IDLE && sendHeadsetHook()) {
                restoreNormalAudio()
                ActionResult.Success("Llamada colgada.")
            } else {
                restoreNormalAudio()
                ActionResult.Error(
                    "Necesito permiso para colgar. Activa «Responder llamadas» para Lázaro en ajustes.",
                )
            }
        }
        return try {
            val ok = endCallWithFallbacks()
            restoreNormalAudio()
            if (ok) ActionResult.Success("Llamada colgada.")
            else ActionResult.Success(
                "He pedido colgar. Si sigue, cuelga en el teléfono o con el botón cancelar del bastón.",
            )
        } catch (e: SecurityException) {
            val hooked = telephonyCallState() != TelephonyManager.CALL_STATE_IDLE && sendHeadsetHook()
            restoreNormalAudio()
            if (hooked) ActionResult.Success("Llamada colgada.")
            else ActionResult.Error("Sin permiso para colgar. Activa el permiso de teléfono para Lázaro.")
        } catch (e: Exception) {
            restoreNormalAudio()
            ActionResult.Error("No pude colgar: ${e.message}")
        }
    }

    fun requestCallConfirmation(contact: ContactMatch): ActionResult = buildCallConfirmation(contact)

    /**
     * Tras contestar/colgar fallido o idle: el MODE_IN_CALL deja el TTS inaudible
     * (sale por auricular). Hay que volver a MODE_NORMAL.
     */
    fun restoreNormalAudio() {
        try {
            val audio = context.getSystemService(AudioManager::class.java) ?: return
            if (audio.mode != AudioManager.MODE_NORMAL) {
                audio.mode = AudioManager.MODE_NORMAL
                Log.i(TAG, "AudioManager mode → NORMAL")
            }
        } catch (e: Exception) {
            Log.w(TAG, "restoreNormalAudio: ${e.message}")
        }
    }

    private fun acceptRingingWithFallbacks(): Boolean {
        val state = telephonyCallState()
        if (state != TelephonyManager.CALL_STATE_RINGING) {
            Log.w(TAG, "accept: no hay RINGING (state=$state); no envío headset hook")
            return false
        }
        var accepted = false
        val telecom = context.getSystemService(TelecomManager::class.java)
        if (telecom != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasAnswerPermission()) {
            try {
                telecom.acceptRingingCall()
                accepted = true
                Log.i(TAG, "acceptRingingCall OK")
            } catch (e: Exception) {
                Log.w(TAG, "acceptRingingCall falló: ${e.message}")
            }
        }
        // Solo hook si aún suena (evita play/pause de media y MODE_IN_CALL fantasma).
        if (telephonyCallState() == TelephonyManager.CALL_STATE_RINGING) {
            val hook = sendHeadsetHook()
            return accepted || hook
        }
        return accepted || telephonyCallState() == TelephonyManager.CALL_STATE_OFFHOOK
    }

    private fun endCallWithFallbacks(): Boolean {
        val state = telephonyCallState()
        if (state == TelephonyManager.CALL_STATE_IDLE) {
            Log.i(TAG, "endCall: ya IDLE, no hook")
            return true
        }
        var ended = false
        val telecom = context.getSystemService(TelecomManager::class.java)
        if (telecom != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hasAnswerPermission()) {
            try {
                ended = telecom.endCall()
                Log.i(TAG, "endCall → $ended")
            } catch (e: Exception) {
                Log.w(TAG, "endCall falló: ${e.message}")
            }
        }
        if (ended || telephonyCallState() == TelephonyManager.CALL_STATE_IDLE) return true
        return sendHeadsetHook()
    }

    private fun telephonyCallState(): Int {
        return try {
            val tm = context.getSystemService(TelephonyManager::class.java)
            tm?.callState ?: TelephonyManager.CALL_STATE_IDLE
        } catch (_: Exception) {
            TelephonyManager.CALL_STATE_IDLE
        }
    }

    /**
     * Simula el botón del auricular: en muchos Samsung contesta o cuelga.
     * Solo usar si hay llamada real (RINGING/OFFHOOK).
     */
    private fun sendHeadsetHook(): Boolean {
        return try {
            val audio = context.getSystemService(AudioManager::class.java)
            if (audio != null) {
                val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK)
                val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK)
                audio.dispatchMediaKeyEvent(down)
                audio.dispatchMediaKeyEvent(up)
                Log.i(TAG, "headset hook via AudioManager")
                return true
            }
            val downIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(
                    Intent.EXTRA_KEY_EVENT,
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK),
                )
            }
            val upIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(
                    Intent.EXTRA_KEY_EVENT,
                    KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK),
                )
            }
            context.sendOrderedBroadcast(downIntent, null)
            context.sendOrderedBroadcast(upIntent, null)
            Log.i(TAG, "headset hook via broadcast")
            true
        } catch (e: Exception) {
            Log.w(TAG, "headset hook falló: ${e.message}")
            false
        }
    }

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
        private const val TAG = "CallAction"
        const val TOOL_ANSWER_INCOMING = "answer_incoming_call"
    }
}
