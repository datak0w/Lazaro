package io.lazaro.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import io.lazaro.MainActivity
import io.lazaro.R
import io.lazaro.assistant.AssistantController
import io.lazaro.cane.CaneBleService
import io.lazaro.cane.CaneButtonMapper
import io.lazaro.cane.CaneRepository
import io.lazaro.cane.CaneTriggerBridge
import io.lazaro.cane.ble.CaneBleManager
import io.lazaro.di.NotificationChannels
import io.lazaro.pathguide.PathGuideController
import io.lazaro.pathguide.PathGuideForegroundBridge
import io.lazaro.pathguide.PathGuideMode
import io.lazaro.sensor.DistanceAlertManager
import io.lazaro.sensor.PiHubBleManager
import io.lazaro.sensor.PiHubRepository
import io.lazaro.sensor.VisionAlertManager
import io.lazaro.memory.LocationTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AssistantForegroundService : Service() {

    @Inject lateinit var assistantController: AssistantController
    @Inject lateinit var locationTracker: LocationTracker
    @Inject lateinit var caneTriggerBridge: CaneTriggerBridge
    @Inject lateinit var caneRepository: CaneRepository
    @Inject lateinit var caneBleManager: CaneBleManager
    @Inject lateinit var buttonMapper: CaneButtonMapper
    @Inject lateinit var piHubBleManager: PiHubBleManager
    @Inject lateinit var piHubRepository: PiHubRepository
    @Inject lateinit var distanceAlertManager: DistanceAlertManager
    @Inject lateinit var visionAlertManager: VisionAlertManager
    @Inject lateinit var pathGuideController: PathGuideController
    @Inject lateinit var pathGuideForegroundBridge: PathGuideForegroundBridge

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var hubAlertsBound = false

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.create(this)
        assistantController.bind(serviceScope)
        serviceScope.launch {
            caneTriggerBridge.triggers.collect {
                assistantController.interruptAndListen()
            }
        }
        serviceScope.launch {
            combine(caneBleManager.bleEvents, caneRepository.config) { event, config ->
                event to config
            }.collect { (event, config) ->
                buttonMapper.onBleEvent(event, config)
            }
        }
        bindHubAlertsOnce()
        pathGuideForegroundBridge.attach { includeCamera ->
            if (!serviceStarted) return@attach
            try {
                promoteForegroundServiceType(includeCamera = includeCamera)
            } catch (e: Exception) {
                android.util.Log.e("Lazaro", "No se pudo actualizar FGS de cámara: ${e.message}", e)
            }
        }
        serviceScope.launch {
            pathGuideController.mode.collect { mode ->
                if (!serviceStarted) return@collect
                try {
                    promoteForegroundServiceType(includeCamera = mode != PathGuideMode.OFF)
                } catch (e: Exception) {
                    android.util.Log.e("Lazaro", "No se pudo actualizar FGS de cámara: ${e.message}", e)
                }
            }
        }
    }

    private fun bindHubAlertsOnce() {
        if (hubAlertsBound) return
        hubAlertsBound = true
        distanceAlertManager.bind(piHubBleManager, piHubRepository)
        visionAlertManager.bind(piHubBleManager, piHubRepository)
    }

    private var serviceStarted = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                assistantController.stopAssistant()
                serviceStarted = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                startService(
                    Intent(this, CaneBleService::class.java).apply { action = CaneBleService.ACTION_STOP },
                )
                return START_NOT_STICKY
            }
            ACTION_START_LISTENING -> {
                ensureServiceStarted()
                serviceScope.launch {
                    delay(700)
                    assistantController.interruptAndListen()
                }
            }
            else -> ensureServiceStarted()
        }
        return START_STICKY
    }

    private fun ensureServiceStarted() {
        try {
            promoteForegroundServiceType(includeCamera = false)
        } catch (e: Exception) {
            android.util.Log.e("Lazaro", "startForeground asistente falló: ${e.message}", e)
            assistantController.setServiceRunning(false)
            stopSelf()
            return
        }
        if (!serviceStarted) {
            serviceStarted = true
            serviceScope.launch {
                assistantController.initializeVoice()
                assistantController.setServiceRunning(true)
                assistantController.startAssistant()
                startLocationTracking()
                startCaneServiceIfConfigured()
                startPiHubIfConfigured()
            }
        }
    }

    private fun promoteForegroundServiceType(includeCamera: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (includeCamera &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                hasCameraPermission()
            ) {
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                serviceType,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startPiHubIfConfigured() {
        serviceScope.launch {
            try {
                val config = piHubRepository.config.first()
                val mac = config.savedMac ?: return@launch
                if (!piHubBleManager.state.value.isConnected) {
                    piHubBleManager.connect(mac, config.savedName)
                }
                if (config.visionAutoIntervalSec > 0) {
                    piHubBleManager.setVisionAutoInterval(config.visionAutoIntervalSec)
                }
            } catch (e: Exception) {
                android.util.Log.e("Lazaro", "No se pudo conectar LazaroHub: ${e.message}", e)
            }
        }
    }

    private fun startCaneServiceIfConfigured() {
        serviceScope.launch {
            try {
                val config = caneRepository.config.first()
                val mac = config.savedMac ?: return@launch
                // Conectar BLE desde el gestor (siempre); FGS del bastón es opcional
                if (!caneBleManager.state.value.isConnected) {
                    caneBleManager.connect(mac, config.savedName)
                }
                ContextCompat.startForegroundService(
                    this@AssistantForegroundService,
                    Intent(this@AssistantForegroundService, CaneBleService::class.java),
                )
            } catch (e: Exception) {
                android.util.Log.e("Lazaro", "No se pudo iniciar servicio del bastón: ${e.message}", e)
            }
        }
    }

    override fun onDestroy() {
        pathGuideForegroundBridge.detach()
        pathGuideController.stop()
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
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.stop_assistant), stopIntent)
            .build()
    }

    companion object {
        const val ACTION_STOP = "io.lazaro.action.STOP_ASSISTANT"
        const val ACTION_START_LISTENING = "io.lazaro.action.START_LISTENING"
        private const val NOTIFICATION_ID = 1001
        private const val LOCATION_INTERVAL_MS = 5 * 60 * 1000L
    }
}
