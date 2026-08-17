package io.lazaro.assistant

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.assistantPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    "assistant_lifecycle_prefs",
)

/**
 * Preferencias de ciclo de vida: asistente deseado activo (para BOOT) y
 * si ya se pidió ignorar optimización de batería.
 */
@Singleton
class AssistantPrefsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore = context.assistantPrefsDataStore

    val wantsAssistantRunning: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_WANTS_ASSISTANT] ?: false
    }

    val batteryOptPrompted: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_BATTERY_OPT_PROMPTED] ?: false
    }

    suspend fun setWantsAssistantRunning(enabled: Boolean) {
        dataStore.edit { it[KEY_WANTS_ASSISTANT] = enabled }
    }

    suspend fun markBatteryOptPrompted() {
        dataStore.edit { it[KEY_BATTERY_OPT_PROMPTED] = true }
    }

    suspend fun wantsAssistantRunningNow(): Boolean = wantsAssistantRunning.first()

    suspend fun wasBatteryOptPrompted(): Boolean = batteryOptPrompted.first()

    companion object {
        private val KEY_WANTS_ASSISTANT = booleanPreferencesKey("wants_assistant_running")
        private val KEY_BATTERY_OPT_PROMPTED = booleanPreferencesKey("battery_opt_prompted")
    }
}
