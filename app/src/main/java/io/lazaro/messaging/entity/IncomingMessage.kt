package io.lazaro.messaging.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "incoming_messages")
data class IncomingMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appLabel: String,
    val sender: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val read: Boolean = false,
)

object MessageApps {
    const val WHATSAPP = "com.whatsapp"
    const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    const val SMS = "com.google.android.apps.messaging"
    const val TELEGRAM = "org.telegram.messenger"

    val SUPPORTED = setOf(WHATSAPP, WHATSAPP_BUSINESS, SMS, TELEGRAM)

    fun labelFor(packageName: String): String = when (packageName) {
        WHATSAPP, WHATSAPP_BUSINESS -> "WhatsApp"
        SMS -> "SMS"
        TELEGRAM -> "Telegram"
        else -> "Mensaje"
    }
}
