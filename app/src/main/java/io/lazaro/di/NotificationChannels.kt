package io.lazaro.di

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import io.lazaro.R

object NotificationChannels {
    const val ASSISTANT_CHANNEL_ID = "lazaro_assistant"
    const val WAKE_WORD_CHANNEL_ID = "lazaro_wake_word"
    const val CANE_CHANNEL_ID = "lazaro_cane"

    fun create(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)

        val assistantChannel = NotificationChannel(
            ASSISTANT_CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = context.getString(R.string.notification_channel_desc)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(assistantChannel)

        val caneChannel = NotificationChannel(
            CANE_CHANNEL_ID,
            context.getString(R.string.cane_notification_title),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
        }
        manager.createNotificationChannel(caneChannel)

        val wakeWordChannel = NotificationChannel(
            WAKE_WORD_CHANNEL_ID,
            context.getString(R.string.wake_word_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.wake_word_channel_desc)
            enableVibration(true)
            setShowBadge(false)
            val audio = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, audio)
        }
        manager.createNotificationChannel(wakeWordChannel)
    }
}
