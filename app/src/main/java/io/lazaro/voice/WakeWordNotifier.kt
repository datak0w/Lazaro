package io.lazaro.voice

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.lazaro.R
import io.lazaro.di.NotificationChannels
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WakeWordNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val listeningCueTone: ListeningCueTone,
) {
    /**
     * Feedback al detectar «Lázaro» / empezar a escuchar:
     * - notificación de sistema **siempre**
     * - vibración **siempre**
     * - tono PCM vía [ListeningCueTone] (Samsung + Pixel; no ToneGenerator)
     */
    fun playActivationSound() {
        showListeningNotification()
        pulseVibration()
        listeningCueTone.play()
    }

    fun showListeningNotification(statusText: String? = null) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent()
        launch.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val pending = PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = statusText
            ?: context.getString(R.string.wake_word_notification_text)
        val notification = NotificationCompat.Builder(context, NotificationChannels.WAKE_WORD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.wake_word_notification_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .setTimeoutAfter(NOTIFICATION_TIMEOUT_MS)
            .build()
        try {
            nm.notify(WAKE_NOTIFICATION_ID, notification)
        } catch (_: Exception) {
            // Sin permiso POST_NOTIFICATIONS: el resto del feedback sigue.
        }
    }

    fun clearListeningNotification() {
        try {
            context.getSystemService(NotificationManager::class.java)
                ?.cancel(WAKE_NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }

    private fun pulseVibration() {
        val vibrator = vibrator() ?: return
        try {
            val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                VibrationEffect.createOneShot(120, 180)
            } else {
                @Suppress("DEPRECATION")
                VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            vibrator.vibrate(effect)
        } catch (_: Exception) {
        }
    }

    private fun vibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    companion object {
        private const val WAKE_NOTIFICATION_ID = 4_201
        private const val NOTIFICATION_TIMEOUT_MS = 12_000L
    }
}
