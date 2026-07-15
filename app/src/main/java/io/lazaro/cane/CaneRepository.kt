package io.lazaro.cane

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.caneDataStore: DataStore<Preferences> by preferencesDataStore("cane_prefs")

data class CaneConfig(
    val savedMac: String? = null,
    val savedName: String? = null,
    val wizardCompleted: Boolean = false,
    val primaryButtonHex: String? = null,
    val primaryButtonCharUuid: String? = null,
)

@Singleton
class CaneRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore = context.caneDataStore

    val config: Flow<CaneConfig> = dataStore.data.map { prefs ->
        CaneConfig(
            savedMac = prefs[KEY_MAC],
            savedName = prefs[KEY_NAME],
            wizardCompleted = prefs[KEY_WIZARD_DONE] ?: false,
            primaryButtonHex = prefs[KEY_BTN_HEX],
            primaryButtonCharUuid = prefs[KEY_BTN_UUID],
        )
    }

    suspend fun saveDevice(mac: String, name: String?) {
        dataStore.edit { prefs ->
            prefs[KEY_MAC] = mac
            if (!name.isNullOrBlank()) prefs[KEY_NAME] = name
        }
    }

    suspend fun savePrimaryButton(charUuid: String, hex: String) {
        dataStore.edit { prefs ->
            prefs[KEY_BTN_UUID] = charUuid
            prefs[KEY_BTN_HEX] = hex
            prefs[KEY_WIZARD_DONE] = true
        }
    }

    suspend fun markWizardCompleted() {
        dataStore.edit { it[KEY_WIZARD_DONE] = true }
    }

    suspend fun forgetDevice() {
        dataStore.edit { it.clear() }
    }

    companion object {
        private val KEY_MAC = stringPreferencesKey("saved_mac")
        private val KEY_NAME = stringPreferencesKey("saved_name")
        private val KEY_WIZARD_DONE = booleanPreferencesKey("wizard_completed")
        private val KEY_BTN_HEX = stringPreferencesKey("primary_btn_hex")
        private val KEY_BTN_UUID = stringPreferencesKey("primary_btn_uuid")
    }
}
