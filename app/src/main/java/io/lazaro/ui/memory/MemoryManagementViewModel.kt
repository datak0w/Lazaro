package io.lazaro.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.lazaro.R
import io.lazaro.accessibility.AccessibilityAccessHelper
import io.lazaro.cane.CaneBleService
import io.lazaro.cane.CaneRepository
import io.lazaro.cane.ble.CaneBleManager
import io.lazaro.cane.ble.CaneHandshakeCapture
import io.lazaro.sensor.PiHubBleManager
import io.lazaro.sensor.PiHubRepository
import io.lazaro.memory.MemoryRepository
import io.lazaro.memory.entity.CustomSkill
import io.lazaro.memory.entity.LocationRecord
import io.lazaro.memory.entity.MemoryEntry
import io.lazaro.messaging.MessageRepository
import io.lazaro.messaging.NotificationAccessHelper
import io.lazaro.messaging.entity.IncomingMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import javax.inject.Inject

data class MemoryManagementUiState(
    val memories: List<MemoryEntry> = emptyList(),
    val skills: List<CustomSkill> = emptyList(),
    val locations: List<LocationRecord> = emptyList(),
    val messages: List<IncomingMessage> = emptyList(),
    val notificationAccessEnabled: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val statusMessage: String = "",
    val caneMac: String? = null,
    val caneName: String? = null,
    val caneConnected: Boolean = false,
    val caneBattery: Int? = null,
    val caneButtonHex: String? = null,
    val caneCaptureActive: Boolean = false,
    val caneCaptureCount: Int = 0,
    val hubMac: String? = null,
    val hubName: String? = null,
    val hubConnected: Boolean = false,
    val hubDistanceCm: Int = 0,
    val hubWifiOk: Boolean = false,
    val hubApiOk: Boolean = false,
    val distanceAlertCm: Int = 50,
    val distanceAlertsEnabled: Boolean = true,
    val visionAutoIntervalSec: Int = 0,
    val visionTtsEnabled: Boolean = true,
)

@HiltViewModel
class MemoryManagementViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val memoryRepository: MemoryRepository,
    private val messageRepository: MessageRepository,
    private val notificationAccessHelper: NotificationAccessHelper,
    private val accessibilityAccessHelper: AccessibilityAccessHelper,
    private val caneRepository: CaneRepository,
    private val caneBleManager: CaneBleManager,
    private val handshakeCapture: CaneHandshakeCapture,
    private val piHubBleManager: PiHubBleManager,
    private val piHubRepository: PiHubRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MemoryManagementUiState())
    val uiState: StateFlow<MemoryManagementUiState> = _uiState.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            combine(
                combine(
                    caneRepository.config,
                    caneBleManager.state,
                    handshakeCapture.isCapturing,
                    handshakeCapture.entryCount,
                ) { caneConfig, conn, capturing, captureCount ->
                    CaneSnapshot(caneConfig, conn, capturing, captureCount)
                },
                combine(piHubRepository.config, piHubBleManager.state) { hubConfig, hubState ->
                    HubSnapshot(hubConfig, hubState)
                },
            ) { cane, hub ->
                _uiState.value = _uiState.value.copy(
                    caneMac = cane.config.savedMac,
                    caneName = cane.config.savedName,
                    caneConnected = cane.connection.isConnected,
                    caneBattery = cane.connection.batteryPercent,
                    caneButtonHex = cane.config.primaryButtonHex,
                    caneCaptureActive = cane.capturing,
                    caneCaptureCount = cane.captureCount,
                    hubMac = hub.config.savedMac,
                    hubName = hub.config.savedName,
                    hubConnected = hub.state.isConnected,
                    hubDistanceCm = hub.state.distanceCm,
                    hubWifiOk = hub.state.wifiOk,
                    hubApiOk = hub.state.apiOk,
                    distanceAlertCm = hub.config.distanceAlertCm,
                    distanceAlertsEnabled = hub.config.distanceAlertsEnabled,
                    visionAutoIntervalSec = hub.config.visionAutoIntervalSec,
                    visionTtsEnabled = hub.config.visionTtsEnabled,
                )
            }.collect { }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                memories = memoryRepository.getAllMemories(),
                skills = memoryRepository.getAllSkills(),
                locations = memoryRepository.getRecentLocations(30),
                messages = messageRepository.getRecent(30),
                notificationAccessEnabled = notificationAccessHelper.isNotificationListenerEnabled(),
                accessibilityEnabled = accessibilityAccessHelper.isAccessibilityEnabled(),
            )
        }
    }

    fun refreshPermissions() {
        _uiState.value = _uiState.value.copy(
            notificationAccessEnabled = notificationAccessHelper.isNotificationListenerEnabled(),
            accessibilityEnabled = accessibilityAccessHelper.isAccessibilityEnabled(),
        )
    }

    fun deleteMemory(key: String) {
        viewModelScope.launch {
            memoryRepository.deleteMemory(key)
            _uiState.value = _uiState.value.copy(
                statusMessage = "Eliminado: $key",
                memories = memoryRepository.getAllMemories(),
            )
        }
    }

    fun deleteSkill(id: Long) {
        viewModelScope.launch {
            memoryRepository.deleteSkill(id)
            _uiState.value = _uiState.value.copy(
                statusMessage = "Skill eliminado",
                skills = memoryRepository.getAllSkills(),
            )
        }
    }

    fun deleteLocation(id: Long) {
        viewModelScope.launch {
            memoryRepository.deleteLocation(id)
            _uiState.value = _uiState.value.copy(
                statusMessage = "Ubicación eliminada",
                locations = memoryRepository.getRecentLocations(30),
            )
        }
    }

    fun deleteMessage(id: Long) {
        viewModelScope.launch {
            messageRepository.deleteMessage(id)
            _uiState.value = _uiState.value.copy(
                statusMessage = "Mensaje eliminado",
                messages = messageRepository.getRecent(30),
            )
        }
    }

    fun openNotificationSettings() {
        notificationAccessHelper.openNotificationAccessSettings()
    }

    fun openAccessibilitySettings() {
        accessibilityAccessHelper.openAccessibilitySettings()
    }

    fun reconnectCane() {
        viewModelScope.launch {
            val config = caneRepository.config.first()
            val mac = config.savedMac ?: return@launch
            ContextCompat.startForegroundService(
                context,
                Intent(context, CaneBleService::class.java).apply {
                    action = CaneBleService.ACTION_CONNECT
                    putExtra(CaneBleService.EXTRA_MAC, mac)
                    putExtra(CaneBleService.EXTRA_NAME, config.savedName)
                },
            )
            _uiState.value = _uiState.value.copy(statusMessage = "Reconectando bastón…")
        }
    }

    fun retryCaneHandshake() {
        caneBleManager.retryHandshake()
        _uiState.value = _uiState.value.copy(statusMessage = "Reenviando handshake al bastón…")
    }

    fun startHandshakeCapture() {
        caneBleManager.startHandshakeCapture()
        if (caneBleManager.state.value.isConnected) {
            caneBleManager.retryHandshake()
        }
        _uiState.value = _uiState.value.copy(
            statusMessage = "Capturando handshake. Pulsa botones del bastón, luego exporta.",
        )
    }

    fun stopAndExportHandshakeCapture() {
        viewModelScope.launch {
            val conn = caneBleManager.state.value
            caneBleManager.stopHandshakeCapture()
            val file = handshakeCapture.export(conn.deviceName, conn.deviceAddress)
            shareCaptureFile(file)
            _uiState.value = _uiState.value.copy(
                statusMessage = "Captura exportada (${file.name}). Compártela para analizar el protocolo.",
            )
        }
    }

    private fun shareCaptureFile(file: java.io.File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.cane_capture_share_title))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun forgetCane() {
        viewModelScope.launch {
            caneBleManager.disconnect()
            context.startService(
                Intent(context, CaneBleService::class.java).apply { action = CaneBleService.ACTION_STOP },
            )
            caneRepository.forgetDevice()
            _uiState.value = _uiState.value.copy(statusMessage = "Bastón olvidado")
        }
    }

    fun reconnectHub() {
        viewModelScope.launch {
            val config = piHubRepository.config.first()
            val mac = config.savedMac ?: return@launch
            piHubBleManager.connect(mac, config.savedName)
            _uiState.value = _uiState.value.copy(statusMessage = "Reconectando LazaroHub…")
        }
    }

    fun forgetHub() {
        viewModelScope.launch {
            piHubBleManager.disconnect()
            piHubRepository.forgetDevice()
            _uiState.value = _uiState.value.copy(statusMessage = "LazaroHub olvidado")
        }
    }

    fun setDistanceAlertCm(cm: Int) {
        viewModelScope.launch {
            piHubRepository.setDistanceAlertCm(cm)
            _uiState.value = _uiState.value.copy(
                statusMessage = "Umbral de obstáculo: $cm cm",
                distanceAlertCm = cm,
            )
        }
    }

    fun toggleDistanceAlerts(enabled: Boolean) {
        viewModelScope.launch {
            piHubRepository.setDistanceAlertsEnabled(enabled)
            _uiState.value = _uiState.value.copy(
                statusMessage = if (enabled) "Alertas de distancia activadas" else "Alertas de distancia desactivadas",
                distanceAlertsEnabled = enabled,
            )
        }
    }

    fun setVisionAutoInterval(sec: Int) {
        viewModelScope.launch {
            piHubRepository.setVisionAutoIntervalSec(sec)
            if (piHubBleManager.state.value.isConnected) {
                piHubBleManager.setVisionAutoInterval(sec)
            }
            _uiState.value = _uiState.value.copy(
                statusMessage = if (sec == 0) "Visión manual" else "Escaneo automático cada $sec s",
                visionAutoIntervalSec = sec,
            )
        }
    }

    fun toggleVisionTts(enabled: Boolean) {
        viewModelScope.launch {
            piHubRepository.setVisionTtsEnabled(enabled)
            _uiState.value = _uiState.value.copy(
                statusMessage = if (enabled) "Voz de escena activada" else "Voz de escena desactivada",
                visionTtsEnabled = enabled,
            )
        }
    }

    fun scanHubNow() {
        piHubBleManager.requestVisionScan()
        _uiState.value = _uiState.value.copy(statusMessage = "Escaneando escena…")
    }

    private data class CaneSnapshot(
        val config: io.lazaro.cane.CaneConfig,
        val connection: io.lazaro.cane.CaneConnectionState,
        val capturing: Boolean,
        val captureCount: Int,
    )

    private data class HubSnapshot(
        val config: io.lazaro.sensor.PiHubConfig,
        val state: io.lazaro.sensor.PiHubConnectionState,
    )
}
