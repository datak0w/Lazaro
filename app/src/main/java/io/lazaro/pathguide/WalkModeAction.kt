package io.lazaro.pathguide

import io.lazaro.actions.ActionResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WalkModeAction @Inject constructor(
    private val pathGuideController: PathGuideController,
) {
    suspend fun start(): ActionResult {
        if (pathGuideController.currentMode() == PathGuideMode.NAVEGACION) {
            return ActionResult.Error(
                "Ahora mismo estás navegando con Maps. Cancela la ruta antes de iniciar un paseo.",
            )
        }
        if (pathGuideController.currentMode() == PathGuideMode.PASEO) {
            return ActionResult.Success(
                "El modo paseo ya está activo. Te guío con sonidos por la cámara.",
            )
        }
        val started = pathGuideController.start(PathGuideMode.PASEO)
        return if (started) {
            ActionResult.Success(
                "Modo paseo activo. Te guiaré con sonidos por la cámara. " +
                    "Di Lázaro, terminar paseo, para parar.",
            )
        } else {
            ActionResult.Error(
                "No pude activar la cámara. Comprueba que Lazaro tenga permiso de cámara.",
            )
        }
    }

    fun stop(): ActionResult {
        if (pathGuideController.currentMode() != PathGuideMode.PASEO) {
            return ActionResult.Error("No hay ningún paseo activo.")
        }
        pathGuideController.stop()
        return ActionResult.Success("Paseo terminado.")
    }
}
