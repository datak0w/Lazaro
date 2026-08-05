package io.lazaro.cane

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CaneTriggerBridge @Inject constructor() {
    private val _triggers = MutableSharedFlow<CaneButtonAction>(extraBufferCapacity = 16)
    val triggers: SharedFlow<CaneButtonAction> = _triggers.asSharedFlow()

    fun emit(action: CaneButtonAction) {
        _triggers.tryEmit(action)
    }

    /** Compat: botón primario / listen. */
    fun emitCaneButtonPress() {
        emit(CaneButtonAction.LISTEN)
    }
}
