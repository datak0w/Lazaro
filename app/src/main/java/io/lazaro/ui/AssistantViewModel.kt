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
import io.lazaro.service.AssistantForegroundService
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context

@HiltViewModel
class AssistantViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val assistantController: AssistantController,
) : ViewModel() {

    val uiState: StateFlow<AssistantUiState> = assistantController.uiState

    init {
        assistantController.bind(viewModelScope)
        viewModelScope.launch {
            assistantController.initializeVoice()
        }
    }

    fun startAssistant() {
        if (!hasRecordAudioPermission()) return
        ContextCompat.startForegroundService(
            context,
            Intent(context, AssistantForegroundService::class.java),
        )
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

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
