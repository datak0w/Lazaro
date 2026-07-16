package io.lazaro.pathguide

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.pathGuideDataStore: DataStore<Preferences> by preferencesDataStore("path_guide_prefs")

@Singleton
class PathGuideRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore = context.pathGuideDataStore

    val config: Flow<PathGuideConfig> = dataStore.data.map { prefs ->
        PathGuideConfig(
            enabled = prefs[KEY_ENABLED] ?: true,
            sensitivity = prefs[KEY_SENSITIVITY] ?: 1f,
            volume = prefs[KEY_VOLUME] ?: 0.75f,
            frontalAlertsEnabled = prefs[KEY_FRONTAL] ?: true,
            stairAlertsEnabled = prefs[KEY_STAIRS] ?: false,
            doorwayAlertsEnabled = prefs[KEY_DOORWAY] ?: true,
            sceneDescriptionsEnabled = prefs[KEY_SCENE] ?: true,
            sceneDescriptionIntervalSec = prefs[KEY_SCENE_INTERVAL] ?: 30,
            depthEnhancedGuidance = prefs[KEY_DEPTH] ?: true,
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_ENABLED] = enabled }
    }

    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("path_guide_enabled")
        private val KEY_SENSITIVITY = floatPreferencesKey("path_guide_sensitivity")
        private val KEY_VOLUME = floatPreferencesKey("path_guide_volume")
        private val KEY_FRONTAL = booleanPreferencesKey("frontal_alerts_enabled")
        private val KEY_STAIRS = booleanPreferencesKey("stair_alerts_enabled")
        private val KEY_DOORWAY = booleanPreferencesKey("doorway_alerts_enabled")
        private val KEY_SCENE = booleanPreferencesKey("scene_descriptions_enabled")
        private val KEY_SCENE_INTERVAL = intPreferencesKey("scene_description_interval_sec")
        private val KEY_DEPTH = booleanPreferencesKey("depth_enhanced_guidance")
    }
}
