package io.lazaro.pathguide

import io.lazaro.actions.ActionResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WalkModeAction @Inject constructor(
    private val pathGuideController: PathGuideController,
    private val pathGuideRepository: PathGuideRepository,
    private val depthHardwareDetector: DepthHardwareDetector,
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
            val depthMode = pathGuideController.debugState.value?.depthGuidanceMode
                ?: depthHardwareDetector.detect(true).mode
            // Paseo activa arnés automáticamente.
            ActionResult.Success(
                "Modo paseo activo. " +
                    HarnessMountGuidance.startMessage(harnessEnabled = true, depthMode = depthMode),
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

    suspend fun setHarnessMountMode(enabled: Boolean): ActionResult {
        pathGuideRepository.setHarnessMountMode(enabled)
        return if (enabled) {
            ActionResult.Success(
                "Modo arnés activado. ${HarnessMountGuidance.FULL_CUE}",
            )
        } else {
            ActionResult.Success(
                "Modo arnés desactivado. Puedes llevar el teléfono en la mano o el bolsillo; " +
                    "la cámara verá peor la acera.",
            )
        }
    }
}
