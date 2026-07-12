package io.lazaro.navigation

import android.content.Context
import android.os.Bundle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import io.lazaro.voice.TextToSpeechManager

@Singleton
class NavigationGuidanceMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val textToSpeechManager: TextToSpeechManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val active = AtomicBoolean(false)
    private var lastAnnounced: String? = null

    fun startNavigation() {
        active.set(true)
        lastAnnounced = null
    }

    fun stopNavigation() {
        active.set(false)
        lastAnnounced = null
        textToSpeechManager.stop()
    }

    fun isNavigationActive(): Boolean = active.get()

    fun onMapsNotification(extras: Bundle) {
        if (!active.get()) return

        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val bigText = extras.getCharSequence("android.bigText")?.toString().orEmpty()

        val instruction = MapsNavigationParser.parse(title, text, bigText) ?: return
        if (instruction == lastAnnounced) return
        lastAnnounced = instruction

        scope.launch {
            TurnHapticFeedback.pulseForInstruction(context, instruction)
            textToSpeechManager.speak(instruction)
        }
    }

    fun onMapsNotificationRemoved() {
        // Mantenemos la sesión activa: Maps actualiza notificaciones con frecuencia.
    }
}
