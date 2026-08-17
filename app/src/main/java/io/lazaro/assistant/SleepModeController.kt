package io.lazaro.assistant

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sleepModeDataStore: DataStore<Preferences> by preferencesDataStore("sleep_mode_prefs")

enum class DoubleVolumeResult {
    /** Primer toque o fuera de ventana: solo bajar volumen. */
    VOLUME_ONLY,
    /** Doble vol− en modo normal → entrar en dormir. */
    ENTER_SLEEP,
    /** Doble vol− en modo dormir → despertar. */
    EXIT_SLEEP,
}

/**
 * Modo dormir estricto: silencio total hasta «Lázaro despierta» o doble volumen abajo.
 */
@Singleton
class SleepModeController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore = context.sleepModeDataStore

    private val _sleeping = MutableStateFlow(false)
    val sleeping: StateFlow<Boolean> = _sleeping.asStateFlow()

    val sleepingFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SLEEPING] ?: false
    }

    @Volatile
    private var lastVolumeDownMs = 0L

    fun isSleeping(): Boolean = _sleeping.value

    suspend fun hydrateFromPrefs() {
        _sleeping.value = sleepingFlow.first()
    }

    suspend fun enterSleep() {
        dataStore.edit { it[KEY_SLEEPING] = true }
        _sleeping.value = true
    }

    suspend fun exitSleep() {
        dataStore.edit { it[KEY_SLEEPING] = false }
        _sleeping.value = false
    }

    /**
     * Doble volumen abajo en &lt; [DOUBLE_VOLUME_WINDOW_MS].
     * Devuelve si debe entrar/salir de sleep o solo ajustar volumen.
     */
    fun onVolumeDownPressed(): DoubleVolumeResult {
        val now = System.currentTimeMillis()
        val isDouble = now - lastVolumeDownMs in 1 until DOUBLE_VOLUME_WINDOW_MS
        lastVolumeDownMs = if (isDouble) 0L else now
        if (!isDouble) return DoubleVolumeResult.VOLUME_ONLY
        return if (_sleeping.value) DoubleVolumeResult.EXIT_SLEEP else DoubleVolumeResult.ENTER_SLEEP
    }

    fun isSleepCommand(userText: String): Boolean = matchesSleepCommand(userText)

    fun isWakeFromSleepPhrase(userText: String): Boolean = matchesWakeFromSleep(userText)

    companion object {
        private val KEY_SLEEPING = booleanPreferencesKey("sleeping")
        /** Ventana un poco más amplia: doble toque más fácil en bastón. */
        const val DOUBLE_VOLUME_WINDOW_MS = 900L

        private val SLEEP_PHRASES = listOf(
            "ve a dormir",
            "vete a dormir",
            "modo dormir",
            "modo sueno",
            "a dormir",
            "duerme lazaro",
            "lazaro duerme",
            "duermete",
            "silence total",
            "silencio total",
            "apagate",
            "callate y duerme",
        )

        fun matchesSleepCommand(userText: String): Boolean {
            val t = normalize(userText)
            if (t.isBlank()) return false
            return SLEEP_PHRASES.any { t.contains(it) } ||
                (t.contains("dormir") && (t.contains("modo") || t.contains("ve ") || t.startsWith("a "))) ||
                t == "duerme" ||
                t == "a dormir" ||
                t.endsWith(" a dormir") ||
                t.startsWith("duerme ")
        }

        fun matchesWakeFromSleep(userText: String): Boolean {
            val compact = normalize(userText)
                .replace(" ", "")
                .replace("'", "")
            val hasLazaro = compact.contains("lazaro") ||
                compact.contains("lasaro") ||
                compact.contains("lazzaro") ||
                compact.contains("lazarro") ||
                compact.contains("hazaro")
            val hasWake = compact.contains("despierta") ||
                compact.contains("despiertate") ||
                compact.contains("despertar")
            return hasLazaro && hasWake
        }

        private fun normalize(raw: String): String {
            val n = Normalizer.normalize(raw.lowercase().trim(), Normalizer.Form.NFD)
            return n.replace(Regex("\\p{M}+"), "")
                .replace(Regex("[^a-z0-9ñ\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }
}
