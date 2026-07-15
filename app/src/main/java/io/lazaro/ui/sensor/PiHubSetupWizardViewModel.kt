package io.lazaro.ui.sensor

import android.Manifest
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.lazaro.sensor.PiHubBleManager
import io.lazaro.sensor.PiHubConnectionState
import io.lazaro.sensor.PiHubRepository
import io.lazaro.sensor.ScannedPiHubDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.content.Context
import javax.inject.Inject

enum class PiHubWizardStep { PERMISSIONS, SCAN, DONE }

data class PiHubWizardUiState(
    val step: PiHubWizardStep = PiHubWizardStep.PERMISSIONS,
    val connection: PiHubConnectionState = PiHubConnectionState(),
    val scannedDevices: List<ScannedPiHubDevice> = emptyList(),
    val isComplete: Boolean = false,
)

@HiltViewModel
class PiHubSetupWizardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val piHubBleManager: PiHubBleManager,
    private val piHubRepository: PiHubRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PiHubWizardUiState())
    val uiState: StateFlow<PiHubWizardUiState> = _uiState.asStateFlow()

    init {
        piHubBleManager.refreshBluetoothState()
        viewModelScope.launch {
            piHubBleManager.state.collect { state ->
                _uiState.update { it.copy(connection = state) }
                if (state.isConnected && _uiState.value.step == PiHubWizardStep.SCAN) {
                    _uiState.update { it.copy(step = PiHubWizardStep.DONE) }
                }
            }
        }
        viewModelScope.launch {
            piHubBleManager.scannedDevices.collect { devices ->
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
        _uiState.update { it.copy(step = PiHubWizardStep.SCAN) }
        piHubBleManager.startScan()
    }

    fun skipWizard() {
        piHubBleManager.stopScan()
        _uiState.update { it.copy(isComplete = true) }
    }

    fun selectDevice(device: ScannedPiHubDevice) {
        piHubBleManager.stopScan()
        viewModelScope.launch {
            piHubRepository.saveDevice(device.address, device.name)
            piHubBleManager.connect(device)
        }
    }

    fun finish() {
        piHubBleManager.stopScan()
        _uiState.update { it.copy(isComplete = true) }
    }

    override fun onCleared() {
        piHubBleManager.stopScan()
        super.onCleared()
    }
}
