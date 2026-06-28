package com.blueridge.parkwaynav.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

enum class AppearanceMode { LIGHT, DARK, SYSTEM }
enum class VoiceType { DEFAULT, MALE, FEMALE }

/** Immutable snapshot of every user setting; also the unit of backup/restore. */
@Serializable
data class AppSettings(
    // General
    val appearance: AppearanceMode = AppearanceMode.DARK, // default DARK per spec
    val use24HourTime: Boolean = false,
    val language: String = "en",
    val keepScreenOn: Boolean = true,
    val keepScreenOnIgnore: Boolean = false,
    // Navigation
    val batteryOptimizationExempt: Boolean = false,
    val runInBackground: Boolean = true,
    val pipEnabled: Boolean = true,
    // Voice & sound
    val voiceEnabled: Boolean = true,
    val voiceType: VoiceType = VoiceType.DEFAULT,
    val speakingSpeed: Float = 1.0f,
    val playOverBluetooth: Boolean = true,
    val playDuringCall: Boolean = false,
    val muteOnOpen: Boolean = false,
    val setVolumeOnOpen: Boolean = false,
    val volumeOnOpen: Float = 0.7f,
    // Backup & restore
    val autoBackup: Boolean = false,
    val backupFolderUri: String = "",
    val appRemovalLock: Boolean = false,
    // Restore prompt bookkeeping: was location enabled when this backup was made?
    val locationWasEnabled: Boolean = false
)

private val Context.dataStore by preferencesDataStore(name = "brp_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val APPEARANCE = stringPreferencesKey("appearance")
        val USE_24H = booleanPreferencesKey("use_24h")
        val LANGUAGE = stringPreferencesKey("language")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val KEEP_SCREEN_ON_IGNORE = booleanPreferencesKey("keep_screen_on_ignore")
        val BATTERY_OPT = booleanPreferencesKey("battery_opt_exempt")
        val RUN_IN_BG = booleanPreferencesKey("run_in_bg")
        val PIP = booleanPreferencesKey("pip_enabled")
        val VOICE_ENABLED = booleanPreferencesKey("voice_enabled")
        val VOICE_TYPE = stringPreferencesKey("voice_type")
        val SPEAKING_SPEED = floatPreferencesKey("speaking_speed")
        val BLUETOOTH = booleanPreferencesKey("play_bluetooth")
        val DURING_CALL = booleanPreferencesKey("play_during_call")
        val MUTE_ON_OPEN = booleanPreferencesKey("mute_on_open")
        val SET_VOLUME_ON_OPEN = booleanPreferencesKey("set_volume_on_open")
        val VOLUME_ON_OPEN = floatPreferencesKey("volume_on_open")
        val AUTO_BACKUP = booleanPreferencesKey("auto_backup")
        val BACKUP_FOLDER = stringPreferencesKey("backup_folder_uri")
        val APP_REMOVAL_LOCK = booleanPreferencesKey("app_removal_lock")
        val LOCATION_WAS_ENABLED = booleanPreferencesKey("location_was_enabled")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    private fun Preferences.toSettings() = AppSettings(
        appearance = this[Keys.APPEARANCE]?.let { runCatching { AppearanceMode.valueOf(it) }.getOrNull() }
            ?: AppearanceMode.DARK,
        use24HourTime = this[Keys.USE_24H] ?: false,
        language = this[Keys.LANGUAGE] ?: "en",
        keepScreenOn = this[Keys.KEEP_SCREEN_ON] ?: true,
        keepScreenOnIgnore = this[Keys.KEEP_SCREEN_ON_IGNORE] ?: false,
        batteryOptimizationExempt = this[Keys.BATTERY_OPT] ?: false,
        runInBackground = this[Keys.RUN_IN_BG] ?: true,
        pipEnabled = this[Keys.PIP] ?: true,
        voiceEnabled = this[Keys.VOICE_ENABLED] ?: true,
        voiceType = this[Keys.VOICE_TYPE]?.let { runCatching { VoiceType.valueOf(it) }.getOrNull() }
            ?: VoiceType.DEFAULT,
        speakingSpeed = this[Keys.SPEAKING_SPEED] ?: 1.0f,
        playOverBluetooth = this[Keys.BLUETOOTH] ?: true,
        playDuringCall = this[Keys.DURING_CALL] ?: false,
        muteOnOpen = this[Keys.MUTE_ON_OPEN] ?: false,
        setVolumeOnOpen = this[Keys.SET_VOLUME_ON_OPEN] ?: false,
        volumeOnOpen = this[Keys.VOLUME_ON_OPEN] ?: 0.7f,
        autoBackup = this[Keys.AUTO_BACKUP] ?: false,
        backupFolderUri = this[Keys.BACKUP_FOLDER] ?: "",
        appRemovalLock = this[Keys.APP_REMOVAL_LOCK] ?: false,
        locationWasEnabled = this[Keys.LOCATION_WAS_ENABLED] ?: false
    )

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.APPEARANCE] = next.appearance.name
            prefs[Keys.USE_24H] = next.use24HourTime
            prefs[Keys.LANGUAGE] = next.language
            prefs[Keys.KEEP_SCREEN_ON] = next.keepScreenOn
            prefs[Keys.KEEP_SCREEN_ON_IGNORE] = next.keepScreenOnIgnore
            prefs[Keys.BATTERY_OPT] = next.batteryOptimizationExempt
            prefs[Keys.RUN_IN_BG] = next.runInBackground
            prefs[Keys.PIP] = next.pipEnabled
            prefs[Keys.VOICE_ENABLED] = next.voiceEnabled
            prefs[Keys.VOICE_TYPE] = next.voiceType.name
            prefs[Keys.SPEAKING_SPEED] = next.speakingSpeed
            prefs[Keys.BLUETOOTH] = next.playOverBluetooth
            prefs[Keys.DURING_CALL] = next.playDuringCall
            prefs[Keys.MUTE_ON_OPEN] = next.muteOnOpen
            prefs[Keys.SET_VOLUME_ON_OPEN] = next.setVolumeOnOpen
            prefs[Keys.VOLUME_ON_OPEN] = next.volumeOnOpen
            prefs[Keys.AUTO_BACKUP] = next.autoBackup
            prefs[Keys.BACKUP_FOLDER] = next.backupFolderUri
            prefs[Keys.APP_REMOVAL_LOCK] = next.appRemovalLock
            prefs[Keys.LOCATION_WAS_ENABLED] = next.locationWasEnabled
        }
    }

    suspend fun replaceAll(settings: AppSettings) = update { settings }
}
