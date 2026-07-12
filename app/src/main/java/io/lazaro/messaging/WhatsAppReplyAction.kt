package io.lazaro.messaging

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
import dagger.hilt.android.qualifiers.ApplicationContext
import io.lazaro.accessibility.AccessibilityAccessHelper
import io.lazaro.accessibility.WhatsAppSendCoordinator
import io.lazaro.actions.ActionResult
import io.lazaro.actions.PendingAction
import io.lazaro.contacts.ContactResolver
import io.lazaro.messaging.entity.MessageApps
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WhatsAppReplyAction @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactResolver: ContactResolver,
    private val replyContext: ReplyContext,
    private val notificationAccessHelper: NotificationAccessHelper,
    private val accessibilityAccessHelper: AccessibilityAccessHelper,
) {
    suspend fun prepareReply(recipient: String?, message: String): ActionResult {
        if (message.isBlank()) {
            return ActionResult.Error("¿Qué quieres responder?")
        }

        if (!notificationAccessHelper.isNotificationListenerEnabled()) {
            notificationAccessHelper.openNotificationAccessSettings()
            return ActionResult.Error(
                "Necesito acceso a notificaciones para responder por WhatsApp. Activa Lazaro en ajustes.",
            )
        }

        val targetName = replyContext.resolveRecipient(recipient)
            ?: return ActionResult.Error(
                "¿A quién quieres responder? Di por ejemplo: responde a María que llego tarde.",
            )

        val contact = contactResolver.findSingleOrNull(targetName)
            ?: contactResolver.findContacts(targetName).firstOrNull()
            ?: return ActionResult.Error(
                "No encuentro el contacto $targetName para responder por WhatsApp.",
            )

        return ActionResult.NeedsConfirmation(
            prompt = "¿Confirmas enviar a ${contact.displayName}: \"$message\"? Di sí o no.",
            pendingAction = PendingAction(
                toolName = "reply_message",
                args = mapOf(
                    "recipient" to contact.displayName,
                    "phone_number" to contact.phoneNumber,
                    "message" to message,
                    "package" to (replyContext.lastSenderPackage ?: MessageApps.WHATSAPP),
                ),
            ),
        )
    }

    fun executeReply(args: Map<String, String>): ActionResult {
        val recipient = args["recipient"].orEmpty()
        val phone = args["phone_number"].orEmpty()
        val message = args["message"].orEmpty()
        val pkg = args["package"].orEmpty().ifBlank { MessageApps.WHATSAPP }

        val viaNotification = LazaroNotificationListenerService.replyToActiveNotification(
            senderName = recipient,
            message = message,
            packageName = pkg,
        )
        if (viaNotification) {
            return ActionResult.Success("Mensaje enviado a $recipient por WhatsApp.")
        }

        return sendViaWhatsAppIntent(phone, message, recipient)
    }

    private fun sendViaWhatsAppIntent(
        phone: String,
        message: String,
        recipient: String,
    ): ActionResult {
        if (!accessibilityAccessHelper.isAccessibilityEnabled()) {
            accessibilityAccessHelper.openAccessibilitySettings()
            return ActionResult.Error(
                "Para enviar automáticamente necesito accesibilidad activada. " +
                    "Activa Lazaro en ajustes de accesibilidad e inténtalo de nuevo.",
            )
        }

        val normalizedPhone = contactResolver.normalizePhone(phone).removePrefix("+")
        val uri = android.net.Uri.parse(
            "https://api.whatsapp.com/send?phone=$normalizedPhone&text=${android.net.Uri.encode(message)}",
        )
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(MessageApps.WHATSAPP)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            WhatsAppSendCoordinator.requestSend()
            context.startActivity(intent)
            ActionResult.Success("Enviando mensaje a $recipient por WhatsApp.")
        } catch (e: Exception) {
            ActionResult.Error("No pude abrir WhatsApp: ${e.localizedMessage ?: "error"}.")
        }
    }
}
