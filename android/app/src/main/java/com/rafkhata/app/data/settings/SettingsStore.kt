package com.rafkhata.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Which Android audio source the recorder uses. */
enum class MicSource { VOICE_RECOGNITION, MIC, CAMCORDER, UNPROCESSED }

data class AppSettings(
    val wifiOnlyUploads: Boolean = false,
    val deadlineReminders: Boolean = true,
    val mutedDeadlines: Set<String> = emptySet(),
    val micSource: MicSource = MicSource.VOICE_RECOGNITION,
    val dismissedBatteryTip: Boolean = false,
)

class SettingsStore(private val context: Context) {
    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            wifiOnlyUploads = p[WIFI_ONLY] ?: false,
            deadlineReminders = p[REMINDERS] ?: true,
            mutedDeadlines = p[MUTED] ?: emptySet(),
            micSource = p[MIC]?.let { runCatching { MicSource.valueOf(it) }.getOrNull() } ?: MicSource.VOICE_RECOGNITION,
            dismissedBatteryTip = p[BATTERY_TIP] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setWifiOnly(value: Boolean) = context.dataStore.edit { it[WIFI_ONLY] = value }

    suspend fun setDeadlineReminders(value: Boolean) = context.dataStore.edit { it[REMINDERS] = value }

    suspend fun setMicSource(value: MicSource) = context.dataStore.edit { it[MIC] = value.name }

    suspend fun dismissBatteryTip() = context.dataStore.edit { it[BATTERY_TIP] = true }

    suspend fun setDeadlineMuted(id: String, muted: Boolean) = context.dataStore.edit {
        val current = it[MUTED] ?: emptySet()
        it[MUTED] = if (muted) current + id else current - id
    }

    suspend fun clear() = context.dataStore.edit { it.clear() }

    private companion object {
        val WIFI_ONLY = booleanPreferencesKey("wifi_only_uploads")
        val REMINDERS = booleanPreferencesKey("deadline_reminders")
        val MUTED = stringSetPreferencesKey("muted_deadlines")
        val MIC = stringPreferencesKey("mic_source")
        val BATTERY_TIP = booleanPreferencesKey("dismissed_battery_tip")
    }
}
