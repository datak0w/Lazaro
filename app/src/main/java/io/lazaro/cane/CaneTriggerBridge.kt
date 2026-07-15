package io.lazaro.cane

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CaneTriggerBridge @Inject constructor() {
    private val _triggers = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val triggers: SharedFlow<Unit> = _triggers.asSharedFlow()

    fun emitCaneButtonPress() {
        _triggers.tryEmit(Unit)
    }
}
