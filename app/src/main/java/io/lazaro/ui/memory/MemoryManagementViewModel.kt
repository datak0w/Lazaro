package io.lazaro.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.lazaro.accessibility.AccessibilityAccessHelper
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
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MemoryManagementUiState(
    val memories: List<MemoryEntry> = emptyList(),
    val skills: List<CustomSkill> = emptyList(),
    val locations: List<LocationRecord> = emptyList(),
    val messages: List<IncomingMessage> = emptyList(),
    val notificationAccessEnabled: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val statusMessage: String = "",
)

@HiltViewModel
class MemoryManagementViewModel @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val messageRepository: MessageRepository,
    private val notificationAccessHelper: NotificationAccessHelper,
    private val accessibilityAccessHelper: AccessibilityAccessHelper,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MemoryManagementUiState())
    val uiState: StateFlow<MemoryManagementUiState> = _uiState.asStateFlow()

    init {
        refresh()
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
}
