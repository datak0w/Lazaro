package io.lazaro.navigation

import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Las fotos Gemini automáticas en navegación están desactivadas (demasiado lentas).
 * MediaPipe cubre obstáculos en tiempo real. Gemini queda para «qué ves?» explícito.
 */
@Singleton
class NavigationSceneLookCoordinator @Inject constructor() {
    fun bind(@Suppress("UNUSED_PARAMETER") scope: CoroutineScope) = Unit

    fun onNavigationStarted() = Unit

    fun onNavigationStopped() = Unit
}
