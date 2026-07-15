package io.lazaro.cane

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
import io.lazaro.cane.ble.CaneBleManager
import io.lazaro.di.NotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class CaneBleService : Service() {

    @Inject lateinit var caneBleManager: CaneBleManager
    @Inject lateinit var caneRepository: CaneRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.create(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                caneBleManager.disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                val mac = intent.getStringExtra(EXTRA_MAC) ?: return START_NOT_STICKY
                val name = intent.getStringExtra(EXTRA_NAME)
                startForegroundWithType()
                caneBleManager.connect(mac, name)
            }
            else -> {
                serviceScope.launch {
                    val config = caneRepository.config.first()
                    val mac = config.savedMac
                    if (mac == null) {
                        stopSelf()
                        return@launch
                    }
                    startForegroundWithType()
                    if (!caneBleManager.state.value.isConnected) {
                        caneBleManager.connect(mac, config.savedName)
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundWithType() {
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            android.util.Log.e("CaneBleService", "startForeground falló: ${e.message}", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, NotificationChannels.CANE_CHANNEL_ID)
            .setContentTitle(getString(R.string.cane_notification_title))
            .setContentText(getString(R.string.cane_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "io.lazaro.cane.STOP"
        const val ACTION_CONNECT = "io.lazaro.cane.CONNECT"
        const val EXTRA_MAC = "mac"
        const val EXTRA_NAME = "name"
        private const val NOTIFICATION_ID = 1002
    }
}
