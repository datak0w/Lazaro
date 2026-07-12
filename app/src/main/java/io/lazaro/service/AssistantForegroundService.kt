package io.lazaro.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import io.lazaro.MainActivity
import io.lazaro.R
import io.lazaro.assistant.AssistantController
import io.lazaro.di.NotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import io.lazaro.memory.LocationTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AssistantForegroundService : Service() {

    @Inject lateinit var assistantController: AssistantController
    @Inject lateinit var locationTracker: LocationTracker

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.create(this)
        assistantController.bind(serviceScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                assistantController.stopAssistant()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                    )
                } else {
                    startForeground(NOTIFICATION_ID, buildNotification())
                }
                serviceScope.launch {
                    assistantController.initializeVoice()
                    assistantController.setServiceRunning(true)
                    assistantController.startAssistant()
                    startLocationTracking()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        assistantController.stopAssistant()
        assistantController.setServiceRunning(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startLocationTracking() {
        serviceScope.launch {
            while (isActive) {
                locationTracker.captureCurrentLocation(source = "periodic")
                delay(LOCATION_INTERVAL_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AssistantForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, NotificationChannels.ASSISTANT_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, getString(R.string.stop_assistant), stopIntent)
            .build()
    }

    companion object {
        const val ACTION_STOP = "io.lazaro.action.STOP_ASSISTANT"
        private const val NOTIFICATION_ID = 1001
        private const val LOCATION_INTERVAL_MS = 5 * 60 * 1000L
    }
}
