package com.blueridge.parkwaynav.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BackupBundle(
    val version: Int = 1,
    val settings: AppSettings,
    val routesJson: String
)

/**
 * Privacy-respecting backup/restore. Everything stays local: a backup is a single JSON file
 * the user writes to a folder THEY choose (Storage Access Framework). Nothing is uploaded.
 */
class BackupManager(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val routesRepo: SavedRoutesRepository
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val backupFileName = "parkwaynav_backup.json"

    suspend fun buildBundleJson(locationEnabled: Boolean): String {
        val current = settingsRepo.settings.first().copy(locationWasEnabled = locationEnabled)
        val bundle = BackupBundle(settings = current, routesJson = routesRepo.exportJson())
        return json.encodeToString(BackupBundle.serializer(), bundle)
    }

    /** Writes a backup into the user-selected tree (folder) URI. Returns true on success. */
    suspend fun backupToFolder(folderUri: Uri, locationEnabled: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val tree = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext false
                val existing = tree.findFile(backupFileName)
                val target = existing ?: tree.createFile("application/json", backupFileName)
                ?: return@withContext false
                context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                    out.write(buildBundleJson(locationEnabled).toByteArray())
                } ?: return@withContext false
                true
            } catch (e: Exception) {
                false
            }
        }

    /** Writes a backup to an explicit document URI (ACTION_CREATE_DOCUMENT result). */
    suspend fun backupToFile(fileUri: Uri, locationEnabled: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(fileUri, "wt")?.use { out ->
                    out.write(buildBundleJson(locationEnabled).toByteArray())
                } != null
            } catch (e: Exception) {
                false
            }
        }

    /** Restores settings + routes from a backup document URI. Returns the bundle on success. */
    suspend fun restoreFromFile(fileUri: Uri): BackupBundle? = withContext(Dispatchers.IO) {
        try {
            val text = context.contentResolver.openInputStream(fileUri)?.bufferedReader()
                ?.use { it.readText() } ?: return@withContext null
            val bundle = json.decodeFromString(BackupBundle.serializer(), text)
            settingsRepo.replaceAll(bundle.settings)
            routesRepo.importJson(bundle.routesJson)
            bundle
        } catch (e: Exception) {
            null
        }
    }

    /** Fired whenever settings change, if auto-backup is enabled with a chosen folder. */
    suspend fun maybeAutoBackup(locationEnabled: Boolean) {
        val s = settingsRepo.settings.first()
        if (s.autoBackup && s.backupFolderUri.isNotBlank()) {
            runCatching { backupToFolder(Uri.parse(s.backupFolderUri), locationEnabled) }
        }
    }
}
