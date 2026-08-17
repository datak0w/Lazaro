package io.lazaro.messaging

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.lazaro.accessibility.AccessibilityAccessHelper
import io.lazaro.accessibility.WhatsAppSendCoordinator
import io.lazaro.actions.ActionResult
import io.lazaro.actions.PendingAction
import io.lazaro.contacts.ContactResolver
import io.lazaro.messaging.entity.MessageApps
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Envío de texto por WhatsApp (nunca SMS).
 * Ruta: 1) RemoteInput solo en notificación WhatsApp/Business
 *       2) Intent api.whatsapp.com / wa.me con paquete forzado a WhatsApp
 */
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

        // Siempre WhatsApp, aunque el último mensaje leído fuera SMS.
        val waPackage = resolveWhatsAppPackage(replyContext.lastSenderPackage)

        return ActionResult.NeedsConfirmation(
            prompt = "¿Confirmas enviar por WhatsApp a ${contact.displayName}: \"$message\"? Di sí o no.",
            pendingAction = PendingAction(
                toolName = "reply_message",
                args = mapOf(
                    "recipient" to contact.displayName,
                    "phone_number" to contact.phoneNumber,
                    "message" to message,
                    "package" to waPackage,
                    "channel" to "whatsapp",
                ),
            ),
        )
    }

    fun executeReply(args: Map<String, String>): ActionResult {
        val recipient = args["recipient"].orEmpty()
        val phone = args["phone_number"].orEmpty()
        val message = args["message"].orEmpty()
        if (message.isBlank()) {
            return ActionResult.Error("No tengo el texto del mensaje.")
        }

        // Nunca responder por SMS/Telegram aunque el pending traiga ese package.
        val waPackage = resolveWhatsAppPackage(args["package"])

        if (!isWhatsAppInstalled()) {
            return ActionResult.Error(
                "WhatsApp no está instalado. No puedo enviar por WhatsApp.",
            )
        }

        val viaNotification = LazaroNotificationListenerService.replyToWhatsAppNotification(
            senderName = recipient,
            message = message,
        )
        if (viaNotification) {
            return ActionResult.Success("Mensaje enviado a $recipient por WhatsApp.")
        }

        return sendViaWhatsAppIntent(phone, message, recipient, waPackage)
    }

    private fun sendViaWhatsAppIntent(
        phone: String,
        message: String,
        recipient: String,
        waPackage: String,
    ): ActionResult {
        if (!accessibilityAccessHelper.isAccessibilityEnabled()) {
            accessibilityAccessHelper.openAccessibilitySettings()
            return ActionResult.Error(
                "Para enviar automáticamente por WhatsApp necesito accesibilidad activada. " +
                    "Activa Lazaro en ajustes de accesibilidad e inténtalo de nuevo.",
            )
        }

        val waPhone = contactResolver.toWhatsAppPhoneDigits(phone)
        if (waPhone.length < 10) {
            return ActionResult.Error(
                "No tengo un número válido de WhatsApp para $recipient. " +
                    "Guárdalo en contactos con prefijo internacional y vuelve a pedírmelo.",
            )
        }

        val uri = android.net.Uri.parse(
            "https://api.whatsapp.com/send?phone=$waPhone&text=${android.net.Uri.encode(message)}",
        )

        val packagesToTry = listOf(waPackage, MessageApps.WHATSAPP, MessageApps.WHATSAPP_BUSINESS)
            .distinct()

        for (pkg in packagesToTry) {
            if (!isPackageInstalled(pkg)) continue
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                WhatsAppSendCoordinator.requestSend()
                context.startActivity(intent)
                return ActionResult.Success("Enviando mensaje a $recipient por WhatsApp.")
            } catch (_: Exception) {
                // probar siguiente paquete
            }
        }

        return ActionResult.Error(
            "No pude abrir WhatsApp para enviar a $recipient. " +
                "Comprueba que WhatsApp esté instalado.",
        )
    }

    private fun resolveWhatsAppPackage(raw: String?): String {
        return when (raw) {
            MessageApps.WHATSAPP_BUSINESS -> MessageApps.WHATSAPP_BUSINESS
            else -> MessageApps.WHATSAPP
        }
    }

    private fun isWhatsAppInstalled(): Boolean {
        return isPackageInstalled(MessageApps.WHATSAPP) ||
            isPackageInstalled(MessageApps.WHATSAPP_BUSINESS)
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
