package io.lazaro.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.lazaro.assistant.AssistantController
import io.lazaro.assistant.AssistantUiState
import io.lazaro.cane.CaneConnectionState
import io.lazaro.cane.ble.CaneBleManager
import io.lazaro.sensor.PiHubBleManager
import io.lazaro.sensor.PiHubConnectionState
import io.lazaro.service.AssistantForegroundService
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context

@HiltViewModel
class AssistantViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val assistantController: AssistantController,
    caneBleManager: CaneBleManager,
    private val piHubBleManager: PiHubBleManager,
) : ViewModel() {

    val uiState: StateFlow<AssistantUiState> = assistantController.uiState
    val caneState: StateFlow<CaneConnectionState> = caneBleManager.state
    val piHubState: StateFlow<PiHubConnectionState> = piHubBleManager.state

    fun startAssistant() {
        if (!hasRecordAudioPermission()) return
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AssistantForegroundService::class.java),
            )
        } catch (e: Exception) {
            android.util.Log.e("AssistantViewModel", "No se pudo iniciar el servicio", e)
        }
    }

    fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasCorePermissions(): Boolean = hasRecordAudioPermission()

    fun requiredPermissions(): Array<String> {
        return buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.CALL_PHONE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    fun stopAssistant() {
        context.startService(
            Intent(context, AssistantForegroundService::class.java).apply {
                action = AssistantForegroundService.ACTION_STOP
            },
        )
    }

    fun interruptAndListen() {
        assistantController.interruptAndListen()
    }

    fun requestVisionScan() {
        piHubBleManager.requestVisionScan()
    }
}
