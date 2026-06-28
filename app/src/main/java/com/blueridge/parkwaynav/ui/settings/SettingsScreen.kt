package com.blueridge.parkwaynav.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.blueridge.parkwaynav.R
import com.blueridge.parkwaynav.admin.DeviceAdminHelper
import com.blueridge.parkwaynav.data.AppearanceMode
import com.blueridge.parkwaynav.data.VoiceType
import com.blueridge.parkwaynav.nav.TtsManager
import com.blueridge.parkwaynav.ui.MainViewModel
import com.blueridge.parkwaynav.ui.Routes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: MainViewModel, nav: NavController) {
    val context = LocalContext.current
    val settings by app.settings.collectAsState()

    var showUninstallDialog by remember { mutableStateOf(false) }
    var showRestoreLocationDialog by remember { mutableStateOf(false) }

    // Transient TTS for the "test voice" button; mirrors current voice settings.
    val tts = remember { TtsManager(context) }
    DisposableEffect(Unit) {
        tts.init()
        onDispose { tts.shutdown() }
    }
    tts.speakingSpeed = settings.speakingSpeed
    tts.voiceType = settings.voiceType
    tts.languageTag = settings.language

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    // Backup to a single file (ACTION_CREATE_DOCUMENT)
    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { app.backupToFile(it, locationEnabled = hasLocationPermission(context)) }
    }
    // Restore from a file
    val openBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            app.restoreFromFile(it) { ok, locationWasEnabled ->
                if (ok && locationWasEnabled && !hasLocationPermission(context)) {
                    showRestoreLocationDialog = true
                }
            }
        }
    }
    // Choose an auto-backup folder (ACTION_OPEN_DOCUMENT_TREE)
    val chooseFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            app.backupToFolder(it, locationEnabled = hasLocationPermission(context))
        }
    }
    // Device admin enable
    val enableAdmin = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // ---- General ----
            SettingsSection(stringResource(R.string.settings_general)) {
                ChoiceRow(
                    title = stringResource(R.string.appearance),
                    options = listOf(
                        AppearanceMode.LIGHT to stringResource(R.string.appearance_light),
                        AppearanceMode.DARK to stringResource(R.string.appearance_dark),
                        AppearanceMode.SYSTEM to stringResource(R.string.appearance_system)
                    ),
                    selected = settings.appearance,
                    onSelect = { mode -> app.updateSettings { it.copy(appearance = mode) } }
                )
                SwitchRow(
                    title = stringResource(R.string.time_24h),
                    checked = settings.use24HourTime,
                    onChange = { v -> app.updateSettings { it.copy(use24HourTime = v) } }
                )
                ChoiceRow(
                    title = stringResource(R.string.language),
                    options = listOf(
                        "en" to stringResource(R.string.language_en),
                        "es" to stringResource(R.string.language_es)
                    ),
                    selected = settings.language,
                    onSelect = { lang ->
                        app.updateSettings { it.copy(language = lang) }
                        com.blueridge.parkwaynav.util.LocaleHelper.cacheLanguage(context, lang)
                        // Recreate so the new locale takes effect immediately.
                        (context as? android.app.Activity)?.recreate()
                    }
                )
                SwitchRow(
                    title = stringResource(R.string.keep_screen_on),
                    checked = settings.keepScreenOn,
                    onChange = { v -> app.updateSettings { it.copy(keepScreenOn = v) } }
                )
                SwitchRow(
                    title = stringResource(R.string.keep_screen_on_ignore),
                    checked = settings.keepScreenOnIgnore,
                    onChange = { v -> app.updateSettings { it.copy(keepScreenOnIgnore = v) } }
                )
            }

            // ---- Navigation ----
            SettingsSection(stringResource(R.string.settings_navigation)) {
                ClickRow(
                    title = stringResource(R.string.battery_optimization),
                    subtitle = stringResource(R.string.battery_optimization_desc),
                    onClick = { requestIgnoreBatteryOptimizations(context) }
                )
                SwitchRow(
                    title = stringResource(R.string.run_in_background),
                    subtitle = stringResource(R.string.run_in_background_desc),
                    checked = settings.runInBackground,
                    onChange = { v -> app.updateSettings { it.copy(runInBackground = v) } }
                )
                SwitchRow(
                    title = stringResource(R.string.pip_mode),
                    subtitle = stringResource(R.string.pip_mode_desc),
                    checked = settings.pipEnabled,
                    onChange = { v -> app.updateSettings { it.copy(pipEnabled = v) } }
                )
            }

            // ---- Voice & sound ----
            SettingsSection(stringResource(R.string.settings_voice_sound)) {
                ChoiceRow(
                    title = stringResource(R.string.voice_type),
                    options = listOf(
                        VoiceType.DEFAULT to stringResource(R.string.voice_default),
                        VoiceType.MALE to stringResource(R.string.voice_male),
                        VoiceType.FEMALE to stringResource(R.string.voice_female)
                    ),
                    selected = settings.voiceType,
                    onSelect = { v -> app.updateSettings { it.copy(voiceType = v) } }
                )
                SliderRow(
                    title = stringResource(R.string.speaking_speed),
                    value = settings.speakingSpeed,
                    valueRange = 0.5f..2.0f,
                    valueLabel = "${"%.1f".format(settings.speakingSpeed)}×",
                    onChange = { v -> app.updateSettings { it.copy(speakingSpeed = v) } }
                )
                SwitchRow(
                    title = stringResource(R.string.play_over_bluetooth),
                    checked = settings.playOverBluetooth,
                    onChange = { v -> app.updateSettings { it.copy(playOverBluetooth = v) } }
                )
                SwitchRow(
                    title = stringResource(R.string.play_during_call),
                    checked = settings.playDuringCall,
                    onChange = { v -> app.updateSettings { it.copy(playDuringCall = v) } }
                )
                SwitchRow(
                    title = stringResource(R.string.mute_on_open),
                    checked = settings.muteOnOpen,
                    onChange = { v -> app.updateSettings { it.copy(muteOnOpen = v) } }
                )
                SwitchRow(
                    title = stringResource(R.string.set_volume_on_open),
                    checked = settings.setVolumeOnOpen,
                    onChange = { v -> app.updateSettings { it.copy(setVolumeOnOpen = v) } }
                )
                if (settings.setVolumeOnOpen) {
                    SliderRow(
                        title = stringResource(R.string.volume),
                        value = settings.volumeOnOpen,
                        valueRange = 0f..1f,
                        valueLabel = "${(settings.volumeOnOpen * 100).toInt()}%",
                        onChange = { v -> app.updateSettings { it.copy(volumeOnOpen = v) } }
                    )
                }
                ClickRow(
                    title = stringResource(R.string.test_voice),
                    onClick = { tts.speak(context.getString(R.string.voice_test_phrase), flush = true) }
                )
            }

            // ---- Backup & restore ----
            SettingsSection(stringResource(R.string.settings_backup_restore)) {
                ClickRow(
                    title = stringResource(R.string.backup_settings),
                    onClick = { createBackup.launch("parkwaynav_backup.json") }
                )
                ClickRow(
                    title = stringResource(R.string.restore_settings),
                    onClick = { openBackup.launch(arrayOf("application/json")) }
                )
                SwitchRow(
                    title = stringResource(R.string.auto_backup),
                    subtitle = stringResource(R.string.auto_backup_desc),
                    checked = settings.autoBackup,
                    onChange = { v -> app.updateSettings { it.copy(autoBackup = v) } }
                )
                ClickRow(
                    title = stringResource(R.string.choose_backup_folder),
                    subtitle = if (settings.backupFolderUri.isBlank())
                        stringResource(R.string.backup_folder_none)
                    else Uri.parse(settings.backupFolderUri).lastPathSegment ?: settings.backupFolderUri,
                    onClick = { chooseFolder.launch(null) }
                )
                SwitchRow(
                    title = stringResource(R.string.enable_device_admin),
                    subtitle = stringResource(R.string.enable_device_admin_desc),
                    checked = DeviceAdminHelper.isActive(context),
                    onChange = { enable ->
                        if (enable) {
                            enableAdmin.launch(
                                DeviceAdminHelper.enableIntent(
                                    context,
                                    context.getString(R.string.enable_device_admin_desc)
                                )
                            )
                        } else {
                            DeviceAdminHelper.disable(context)
                        }
                    }
                )
                SwitchRow(
                    title = stringResource(R.string.app_enable_lock),
                    subtitle = stringResource(R.string.app_enable_lock_desc),
                    checked = settings.appRemovalLock,
                    onChange = { v -> app.updateSettings { it.copy(appRemovalLock = v) } }
                )
                ClickRow(
                    title = stringResource(R.string.uninstall_app),
                    onClick = { showUninstallDialog = true }
                )
            }

            // ---- About ----
            SettingsSection(stringResource(R.string.settings_about)) {
                ClickRow(
                    title = stringResource(R.string.about),
                    onClick = { nav.navigate(Routes.ABOUT) }
                )
            }
        }
    }

    if (showUninstallDialog) {
        AlertDialog(
            onDismissRequest = { showUninstallDialog = false },
            title = { Text(stringResource(R.string.uninstall_warning_title)) },
            text = { Text(stringResource(R.string.uninstall_warning_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showUninstallDialog = false
                    createBackup.launch("parkwaynav_backup.json")
                }) { Text(stringResource(R.string.uninstall_continue)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUninstallDialog = false
                    DeviceAdminHelper.uninstallSelf(context) // disables device admin first
                }) { Text(stringResource(R.string.uninstall_anyway)) }
            }
        )
    }

    if (showRestoreLocationDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreLocationDialog = false },
            title = { Text(stringResource(R.string.restore_location_title)) },
            text = { Text(stringResource(R.string.restore_location_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreLocationDialog = false
                    locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }) { Text(stringResource(R.string.enable_location)) }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreLocationDialog = false }) {
                    Text(stringResource(R.string.not_now))
                }
            }
        )
    }
}

private fun hasLocationPermission(context: Context): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

private fun requestIgnoreBatteryOptimizations(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                )
            }
        } else {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }
}
