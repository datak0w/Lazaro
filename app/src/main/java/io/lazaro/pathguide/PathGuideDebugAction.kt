package io.lazaro.pathguide

import android.graphics.Bitmap
import io.lazaro.actions.ActionResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PathGuideDebugAction @Inject constructor(
    private val pathGuideController: PathGuideController,
) {
    suspend fun startPreview(): ActionResult {
        if (pathGuideController.currentMode() == PathGuideMode.NAVEGACION) {
            return ActionResult.Error("No se puede abrir la vista de depuración durante la navegación.")
        }
        if (pathGuideController.currentMode() == PathGuideMode.PASEO) {
            return ActionResult.Success("Abre la pantalla de depuración de cámara desde el menú.")
        }
        val started = pathGuideController.start(PathGuideMode.DEBUG)
        return if (started) {
            ActionResult.Success("Vista de depuración de cámara activa.")
        } else {
            ActionResult.Error("No pude abrir la cámara. Comprueba el permiso de cámara.")
        }
    }

    fun stopPreview() {
        if (pathGuideController.currentMode() == PathGuideMode.DEBUG) {
            pathGuideController.stop()
        }
    }
}
