package io.lazaro.navigation

import io.lazaro.pathguide.MapsInstructionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NavigationAudioCoordinator @Inject constructor() {

    private val navigationActive = AtomicBoolean(false)
    private val mapsSpeaking = AtomicBoolean(false)

    private val _lastMapsInstruction = MutableStateFlow<String?>(null)
    val lastMapsInstruction: StateFlow<String?> = _lastMapsInstruction.asStateFlow()

    private var mapsCooldownUntilMs = 0L
    private var lastMapsInstructionMs = 0L
    private var lastTurnSide: TurnSide? = null
    private var turnWindowUntilMs = 0L
    private var lastInstructionType = MapsInstructionType.OTHER
    private var crossSearchUntilMs = 0L

    fun startNavigation() {
        navigationActive.set(true)
        mapsSpeaking.set(false)
        mapsCooldownUntilMs = 0L
        lastMapsInstructionMs = 0L
        lastTurnSide = null
        turnWindowUntilMs = 0L
        lastInstructionType = MapsInstructionType.OTHER
        crossSearchUntilMs = 0L
        _lastMapsInstruction.value = null
    }

    fun stopNavigation() {
        navigationActive.set(false)
        mapsSpeaking.set(false)
        mapsCooldownUntilMs = 0L
        lastTurnSide = null
        turnWindowUntilMs = 0L
        lastInstructionType = MapsInstructionType.OTHER
        crossSearchUntilMs = 0L
        _lastMapsInstruction.value = null
    }

    fun isNavigationActive(): Boolean = navigationActive.get()

    fun onMapsInstructionStarting(instruction: String) {
        mapsSpeaking.set(true)
        val now = System.currentTimeMillis()
        mapsCooldownUntilMs = now + MAPS_BEEP_COOLDOWN_MS
        lastMapsInstructionMs = now
        lastTurnSide = MapsNavigationParser.turnSide(instruction)
        lastInstructionType = MapsNavigationParser.classifyInstruction(instruction)
        if (lastInstructionType == MapsInstructionType.TURN ||
            lastInstructionType == MapsInstructionType.ROUNDABOUT
        ) {
            turnWindowUntilMs = now + TURN_WINDOW_MS
        }
        if (lastInstructionType == MapsInstructionType.CROSS_STREET) {
            crossSearchUntilMs = now + CROSS_SEARCH_MS
        }
        _lastMapsInstruction.value = instruction
    }

    fun onMapsContext(type: MapsInstructionType, rawText: String) {
        lastInstructionType = type
        val now = System.currentTimeMillis()
        when (type) {
            MapsInstructionType.TURN, MapsInstructionType.ROUNDABOUT ->
                turnWindowUntilMs = now + TURN_WINDOW_MS
            MapsInstructionType.CROSS_STREET ->
                crossSearchUntilMs = now + CROSS_SEARCH_MS
            else -> Unit
        }
        if (rawText.isNotBlank()) {
            _lastMapsInstruction.value = rawText
        }
    }

    fun lastInstructionType(): MapsInstructionType = lastInstructionType

    fun isCrossingSearchActive(now: Long = System.currentTimeMillis()): Boolean {
        return navigationActive.get() && now < crossSearchUntilMs
    }

    fun isWithinTurnWindow(now: Long = System.currentTimeMillis()): Boolean {
        return navigationActive.get() && now < turnWindowUntilMs
    }

    fun onMapsInstructionFinished() {
        mapsSpeaking.set(false)
        mapsCooldownUntilMs = System.currentTimeMillis() + MAPS_BEEP_COOLDOWN_MS
    }

    fun shouldDuckBeeps(): Boolean {
        if (!navigationActive.get()) return false
        val now = System.currentTimeMillis()
        return mapsSpeaking.get() || now < mapsCooldownUntilMs
    }

    fun canPathGuideSpeak(urgent: Boolean): Boolean {
        if (!navigationActive.get()) return true
        if (mapsSpeaking.get()) return false
        val now = System.currentTimeMillis()
        if (now < mapsCooldownUntilMs) return urgent
        if (!urgent && now - lastMapsInstructionMs < MIN_GAP_PATHGUIDE_SPEECH_MS) return false
        return true
    }

    fun canSceneDescription(now: Long): Boolean {
        if (!navigationActive.get()) return true
        if (mapsSpeaking.get()) return false
        if (now < mapsCooldownUntilMs) return false
        if (now - lastMapsInstructionMs < MIN_GAP_SCENE_MS) return false
        return true
    }

    fun lastTurnSide(): TurnSide? = lastTurnSide

    fun sceneDescriptionIntervalSec(defaultSec: Int): Int {
        return if (navigationActive.get()) NAV_SCENE_INTERVAL_SEC else defaultSec
    }

    companion object {
        private const val MAPS_BEEP_COOLDOWN_MS = 1_800L
        private const val MIN_GAP_PATHGUIDE_SPEECH_MS = 4_000L
        private const val MIN_GAP_SCENE_MS = 12_000L
        private const val TURN_WINDOW_MS = 22_000L
        const val NAV_SCENE_INTERVAL_SEC = 60
        private const val CROSS_SEARCH_MS = 15_000L
    }
}
