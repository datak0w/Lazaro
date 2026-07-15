package io.lazaro.sensor

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.piHubDataStore: DataStore<Preferences> by preferencesDataStore("pi_hub_prefs")

@Singleton
class PiHubRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore = context.piHubDataStore

    val config: Flow<PiHubConfig> = dataStore.data.map { prefs ->
        PiHubConfig(
            savedMac = prefs[KEY_MAC],
            savedName = prefs[KEY_NAME],
            distanceAlertCm = prefs[KEY_ALERT_CM] ?: DEFAULT_ALERT_CM,
            distanceAlertsEnabled = prefs[KEY_ALERTS_ENABLED] ?: true,
            visionAutoIntervalSec = prefs[KEY_VISION_INTERVAL] ?: 0,
            visionTtsEnabled = prefs[KEY_VISION_TTS] ?: true,
        )
    }

    suspend fun saveDevice(mac: String, name: String?) {
        dataStore.edit { prefs ->
            prefs[KEY_MAC] = mac
            if (!name.isNullOrBlank()) prefs[KEY_NAME] = name
        }
    }

    suspend fun setDistanceAlertCm(cm: Int) {
        dataStore.edit { it[KEY_ALERT_CM] = cm.coerceIn(20, 200) }
    }

    suspend fun setDistanceAlertsEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_ALERTS_ENABLED] = enabled }
    }

    suspend fun setVisionAutoIntervalSec(sec: Int) {
        dataStore.edit { it[KEY_VISION_INTERVAL] = sec.coerceAtLeast(0) }
    }

    suspend fun setVisionTtsEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_VISION_TTS] = enabled }
    }

    suspend fun forgetDevice() {
        dataStore.edit {
            it.remove(KEY_MAC)
            it.remove(KEY_NAME)
        }
    }

    companion object {
        const val DEFAULT_ALERT_CM = 50
        private val KEY_MAC = stringPreferencesKey("saved_mac")
        private val KEY_NAME = stringPreferencesKey("saved_name")
        private val KEY_ALERT_CM = intPreferencesKey("distance_alert_cm")
        private val KEY_ALERTS_ENABLED = booleanPreferencesKey("distance_alerts_enabled")
        private val KEY_VISION_INTERVAL = intPreferencesKey("vision_auto_interval")
        private val KEY_VISION_TTS = booleanPreferencesKey("vision_tts_enabled")
    }
}
