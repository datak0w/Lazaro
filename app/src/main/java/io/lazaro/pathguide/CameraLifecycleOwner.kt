package io.lazaro.pathguide

import androidx.camera.core.ImageProxy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

internal data class GrayFrame(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
)

internal class CameraLifecycleOwner : LifecycleOwner {
    private var registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = registry

    fun start() {
        if (registry.currentState == Lifecycle.State.DESTROYED) {
            registry = LifecycleRegistry(this)
        }
        registry.currentState = Lifecycle.State.CREATED
        registry.currentState = Lifecycle.State.STARTED
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun stop() {
        if (registry.currentState != Lifecycle.State.DESTROYED) {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
