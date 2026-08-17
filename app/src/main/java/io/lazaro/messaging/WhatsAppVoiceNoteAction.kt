package io.lazaro.messaging

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import io.lazaro.accessibility.AccessibilityAccessHelper
import io.lazaro.accessibility.WhatsAppSendCoordinator
import io.lazaro.actions.ActionResult
import io.lazaro.actions.PendingAction
import io.lazaro.contacts.ContactResolver
import io.lazaro.messaging.entity.MessageApps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Graba nota de voz del usuario y la envía por WhatsApp sin tocar la pantalla.
 * Fin de grabación: botón centro del bastón, o timeout automático.
 */
@Singleton
class WhatsAppVoiceNoteAction @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactResolver: ContactResolver,
    private val replyContext: ReplyContext,
    private val accessibilityAccessHelper: AccessibilityAccessHelper,
) {
    @Volatile
    private var armedArgs: Map<String, String>? = null

    @Volatile
    private var recording = false

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var timeoutJob: Job? = null
    private var activeArgs: Map<String, String>? = null

    fun isRecording(): Boolean = recording

    fun hasArmedStart(): Boolean = armedArgs != null

    /** Tras el TTS de «Grabando…», el controlador llama [beginMicCapture]. */
    fun armStart(args: Map<String, String>) {
        armedArgs = args
    }

    fun consumeArmedStart(): Map<String, String>? {
        val args = armedArgs
        armedArgs = null
        return args
    }

    fun cancelArmed() {
        armedArgs = null
    }

    fun prepareOfferReply(recipient: String, phone: String, pkg: String): ActionResult {
        val label = recipient.ifBlank { "el contacto" }
        return ActionResult.NeedsConfirmation(
            prompt = "¿Quieres responder a $label con un mensaje de voz? Di sí, no, o para.",
            pendingAction = PendingAction(
                toolName = TOOL_OFFER_VOICE_REPLY,
                args = mapOf(
                    "recipient" to label,
                    "phone_number" to phone,
                    "package" to pkg.ifBlank { MessageApps.WHATSAPP },
                ),
            ),
        )
    }

    suspend fun prepareSendToContact(recipientQuery: String): ActionResult {
        if (recipientQuery.isBlank()) {
            return ActionResult.NeedsConfirmation(
                prompt = "¿A quién le mando el WhatsApp de voz? Di el nombre del contacto.",
                pendingAction = PendingAction(
                    toolName = TOOL_SELECT_VOICE_RECIPIENT,
                    args = emptyMap(),
                ),
            )
        }
        val matches = contactResolver.findContacts(recipientQuery)
        if (matches.isEmpty()) {
            return ActionResult.Error("No encuentro a $recipientQuery en tus contactos.")
        }
        if (matches.size > 1) {
            val options = matches.take(5).mapIndexed { i, m -> "${i + 1}: ${m.displayName}" }
                .joinToString(". ")
            return ActionResult.NeedsConfirmation(
                prompt = "Encontré varios: $options. Di el número o el nombre.",
                pendingAction = PendingAction(
                    toolName = TOOL_SELECT_VOICE_RECIPIENT,
                    args = matches.take(5).mapIndexed { index, m ->
                        "candidate_$index" to "${m.displayName}|${m.phoneNumber}"
                    }.toMap() + ("query" to recipientQuery),
                ),
            )
        }
        val contact = matches.first()
        return armRecordingPrompt(contact.displayName, contact.phoneNumber)
    }

    fun armRecordingPrompt(recipient: String, phone: String): ActionResult {
        val args = mapOf(
            "recipient" to recipient,
            "phone_number" to phone,
            "package" to (replyContext.lastSenderPackage ?: MessageApps.WHATSAPP),
        )
        armStart(args)
        return ActionResult.Success(
            "Grabando mensaje de voz para $recipient. Habla ahora. " +
                "Pulsa el botón del centro del bastón para enviar, o espera y lo mando solo.",
        )
    }

    fun beginMicCapture(scope: CoroutineScope, args: Map<String, String>): Boolean {
        if (recording) return false
        stopRecorderInternal(discard = true)
        val dir = File(context.cacheDir, "voice_notes").apply { mkdirs() }
        val file = File(dir, "wa_${System.currentTimeMillis()}.m4a")
        return try {
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(96_000)
            mr.setAudioSamplingRate(44_100)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            outputFile = file
            activeArgs = args
            recording = true
            timeoutJob?.cancel()
            timeoutJob = scope.launch {
                delay(MAX_RECORD_MS)
                if (recording) {
                    // El controlador debe llamar finishAndSend; marcamos listo
                    Log.i(TAG, "Timeout grabación WhatsApp")
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo grabar: ${e.message}", e)
            recording = false
            recorder = null
            outputFile = null
            activeArgs = null
            false
        }
    }

    fun isTimedOut(): Boolean {
        val job = timeoutJob ?: return false
        return recording && !job.isActive
    }

    fun finishAndSend(): ActionResult {
        if (!recording && outputFile == null) {
            return ActionResult.Success("") // ya enviado o cancelado
        }
        val args = activeArgs.orEmpty()
        val recipient = args["recipient"].orEmpty().ifBlank { "el contacto" }
        val phone = args["phone_number"].orEmpty()
        val file = stopRecorderInternal(discard = false)
            ?: return ActionResult.Error("No pude guardar el audio.")
        if (file.length() < MIN_BYTES) {
            file.delete()
            return ActionResult.Error("El mensaje ha salido muy corto. Di manda un WhatsApp y vuelve a grabar.")
        }
        return sendAudioFile(phone, recipient, file)
    }

    fun cancelRecording(): ActionResult {
        stopRecorderInternal(discard = true)
        armedArgs = null
        return ActionResult.Success("Grabación cancelada.")
    }

    private fun sendAudioFile(phone: String, recipient: String, file: File): ActionResult {
        if (!accessibilityAccessHelper.isAccessibilityEnabled()) {
            accessibilityAccessHelper.openAccessibilitySettings()
            return ActionResult.Error(
                "Para enviar solo el audio necesito accesibilidad activada. " +
                    "Activa Lazaro en ajustes de accesibilidad e inténtalo de nuevo.",
            )
        }
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            return ActionResult.Error("No pude preparar el audio: ${e.message}")
        }
        val normalized = contactResolver.toWhatsAppPhoneDigits(phone)
        if (normalized.length < 10) {
            return ActionResult.Error(
                "No tengo el número de WhatsApp de $recipient. " +
                    "Guárdalo en contactos con prefijo y vuelve a pedírmelo.",
            )
        }
        val jid = "$normalized@s.whatsapp.net"
        val waPackages = listOf(
            MessageApps.WHATSAPP,
            MessageApps.WHATSAPP_BUSINESS,
        )
        for (pkg in waPackages) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra("jid", jid)
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                WhatsAppSendCoordinator.requestSend()
                context.startActivity(intent)
                return ActionResult.Success("Enviando mensaje de voz a $recipient por WhatsApp.")
            } catch (_: Exception) {
                // siguiente paquete
            }
        }
        return ActionResult.Error(
            "No pude abrir WhatsApp para enviar el audio a $recipient.",
        )
    }

    private fun stopRecorderInternal(discard: Boolean): File? {
        timeoutJob?.cancel()
        timeoutJob = null
        val file = outputFile
        try {
            recorder?.apply {
                try {
                    stop()
                } catch (_: Exception) {
                }
                try {
                    release()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        recorder = null
        recording = false
        activeArgs = null
        outputFile = null
        if (discard) {
            file?.delete()
            return null
        }
        return file
    }

    companion object {
        private const val TAG = "WaVoiceNote"
        const val TOOL_OFFER_VOICE_REPLY = "offer_whatsapp_voice_reply"
        const val TOOL_SELECT_VOICE_RECIPIENT = "select_whatsapp_voice_recipient"
        const val MAX_RECORD_MS = 30_000L
        private const val MIN_BYTES = 2_000L
    }
}
