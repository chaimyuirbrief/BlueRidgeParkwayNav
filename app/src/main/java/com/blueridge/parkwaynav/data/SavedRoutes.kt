package com.blueridge.parkwaynav.data

import android.content.Context
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class SavedStop(val label: String, val lat: Double, val lng: Double) {
    val latLng: LatLng get() = LatLng(lat, lng)
}

@Serializable
data class SavedRoute(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val stops: List<SavedStop>,
    val preferParkway: Boolean = true
)

@Serializable
private data class SavedRoutesFile(val routes: List<SavedRoute> = emptyList())

/** Stores user-planned routes locally (filesDir). Never leaves the device. */
class SavedRoutesRepository private constructor(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val _routes = MutableStateFlow(load())
    val routes: StateFlow<List<SavedRoute>> = _routes.asStateFlow()

    private fun load(): List<SavedRoute> = try {
        if (file.exists()) json.decodeFromString(SavedRoutesFile.serializer(), file.readText()).routes
        else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun persist(routes: List<SavedRoute>) {
        _routes.value = routes
        runCatching { file.writeText(json.encodeToString(SavedRoutesFile.serializer(), SavedRoutesFile(routes))) }
    }

    fun save(route: SavedRoute) {
        val existing = _routes.value.filterNot { it.id == route.id }
        persist(existing + route)
    }

    fun delete(id: String) = persist(_routes.value.filterNot { it.id == id })

    fun get(id: String): SavedRoute? = _routes.value.firstOrNull { it.id == id }

    /** Serialized form for inclusion in a backup. */
    fun exportJson(): String = json.encodeToString(SavedRoutesFile.serializer(), SavedRoutesFile(_routes.value))

    fun importJson(text: String) {
        runCatching {
            persist(json.decodeFromString(SavedRoutesFile.serializer(), text).routes)
        }
    }

    companion object {
        @Volatile private var instance: SavedRoutesRepository? = null
        fun get(context: Context): SavedRoutesRepository =
            instance ?: synchronized(this) {
                instance ?: SavedRoutesRepository(File(context.filesDir, "saved_routes.json"))
                    .also { instance = it }
            }
    }
}
