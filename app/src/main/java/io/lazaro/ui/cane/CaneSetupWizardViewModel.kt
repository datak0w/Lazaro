package io.lazaro.ui.cane

import android.Manifest
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.lazaro.cane.CaneButtonMapper
import io.lazaro.cane.CaneConnectionState
import io.lazaro.cane.CaneRepository
import io.lazaro.cane.ScannedCaneDevice
import io.lazaro.cane.ble.CaneBleManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.lazaro.cane.CaneBleService
import javax.inject.Inject

enum class WizardStep { PERMISSIONS, SCAN, CONNECTED, LEARN }

data class CaneWizardUiState(
    val step: WizardStep = WizardStep.PERMISSIONS,
    val connection: CaneConnectionState = CaneConnectionState(),
    val scannedDevices: List<ScannedCaneDevice> = emptyList(),
    val learnMessage: String = "",
    val learnTimedOut: Boolean = false,
    val isComplete: Boolean = false,
)

@HiltViewModel
class CaneSetupWizardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val caneBleManager: CaneBleManager,
    private val caneRepository: CaneRepository,
    private val buttonMapper: CaneButtonMapper,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CaneWizardUiState())
    val uiState: StateFlow<CaneWizardUiState> = _uiState.asStateFlow()

    private var learnJob: Job? = null

    init {
        caneBleManager.refreshBluetoothState()
        viewModelScope.launch {
            caneBleManager.state.collect { state ->
                _uiState.update { it.copy(connection = state) }
                if (state.isConnected && _uiState.value.step == WizardStep.CONNECTED) {
                    startLearnMode()
                }
            }
        }
        viewModelScope.launch {
            caneBleManager.scannedDevices.collect { devices ->
                _uiState.update { it.copy(scannedDevices = devices) }
            }
        }
    }

    fun bluetoothPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }.toTypedArray()

    fun onPermissionsGranted() {
        _uiState.update { it.copy(step = WizardStep.SCAN) }
        caneBleManager.startScan()
    }

    fun skipWizard() {
        caneBleManager.stopScan()
        viewModelScope.launch { caneRepository.markWizardCompleted() }
        _uiState.update { it.copy(isComplete = true) }
    }

    fun selectDevice(device: ScannedCaneDevice) {
        caneBleManager.stopScan()
        _uiState.update { it.copy(step = WizardStep.CONNECTED, learnMessage = "") }
        viewModelScope.launch {
            caneRepository.saveDevice(device.address, device.name)
            caneBleManager.connect(device)
        }
    }

    private fun startLearnMode() {
        if (learnJob?.isActive == true) return
        _uiState.update {
            it.copy(
                step = WizardStep.LEARN,
                learnMessage = "Pulsa el botón del bastón…",
                learnTimedOut = false,
            )
        }
        learnJob = viewModelScope.launch {
            val timeout = launch {
                delay(60_000)
                _uiState.update {
                    it.copy(learnTimedOut = true, learnMessage = "Tiempo agotado")
                }
            }
            caneBleManager.bleEvents.collect { event ->
                if (!buttonMapper.isLearnCandidate(event)) return@collect
                timeout.cancel()
                caneRepository.savePrimaryButton(event.charUuid, event.hexPayload)
                startCaneService()
                _uiState.update { it.copy(isComplete = true, learnMessage = "Botón guardado") }
                learnJob?.cancel()
            }
        }
    }

    fun finishWithoutButton() {
        viewModelScope.launch {
            caneRepository.markWizardCompleted()
            startCaneService()
        }
        _uiState.update { it.copy(isComplete = true) }
    }

    private fun startCaneService() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, CaneBleService::class.java),
        )
    }

    fun stopScan() = caneBleManager.stopScan()

    override fun onCleared() {
        caneBleManager.stopScan()
        super.onCleared()
    }
}
