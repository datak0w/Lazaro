package io.lazaro.pathguide

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PathGuideForegroundBridge @Inject constructor() {
    @Volatile
    private var promoter: ((Boolean) -> Unit)? = null

    fun attach(promoter: (Boolean) -> Unit) {
        this.promoter = promoter
    }

    fun detach() {
        promoter = null
    }

    fun promoteCameraForeground(includeCamera: Boolean) {
        promoter?.invoke(includeCamera)
    }
}
