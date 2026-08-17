package io.lazaro.alarm

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private val Context.alarmDataStore: DataStore<Preferences> by preferencesDataStore("lazaro_alarms")

@Singleton
class AlarmRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val key = stringPreferencesKey("alarms_json")

    suspend fun getAll(): List<LazaroAlarm> {
        val raw = context.alarmDataStore.data.map { it[key] }.first().orEmpty()
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        LazaroAlarm(
                            id = obj.getLong("id"),
                            hour = obj.getInt("hour"),
                            minute = obj.getInt("minute"),
                            label = obj.optString("label", "Alarma"),
                            enabled = obj.optBoolean("enabled", true),
                            triggerAtEpochMs = obj.getLong("triggerAtEpochMs"),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun saveAll(alarms: List<LazaroAlarm>) {
        val arr = JSONArray()
        for (alarm in alarms) {
            arr.put(
                JSONObject()
                    .put("id", alarm.id)
                    .put("hour", alarm.hour)
                    .put("minute", alarm.minute)
                    .put("label", alarm.label)
                    .put("enabled", alarm.enabled)
                    .put("triggerAtEpochMs", alarm.triggerAtEpochMs),
            )
        }
        context.alarmDataStore.edit { prefs ->
            prefs[key] = arr.toString()
        }
    }

    suspend fun upsert(alarm: LazaroAlarm) {
        val rest = getAll().filterNot { it.id == alarm.id }
        saveAll((rest + alarm).sortedBy { it.triggerAtEpochMs })
    }

    suspend fun remove(id: Long): LazaroAlarm? {
        val all = getAll()
        val removed = all.find { it.id == id }
        if (removed != null) {
            saveAll(all.filterNot { it.id == id })
        }
        return removed
    }

    suspend fun findByTime(hour: Int, minute: Int): LazaroAlarm? {
        return getAll().find { it.enabled && it.hour == hour && it.minute == minute }
    }

    suspend fun nextEnabled(): LazaroAlarm? {
        val now = System.currentTimeMillis()
        return getAll()
            .filter { it.enabled && it.triggerAtEpochMs >= now - 60_000L }
            .minByOrNull { it.triggerAtEpochMs }
    }

    suspend fun enabledAlarms(): List<LazaroAlarm> {
        return getAll().filter { it.enabled }.sortedBy { it.triggerAtEpochMs }
    }
}
