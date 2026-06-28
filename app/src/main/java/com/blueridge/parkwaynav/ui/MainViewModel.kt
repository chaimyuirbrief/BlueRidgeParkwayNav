package com.blueridge.parkwaynav.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blueridge.parkwaynav.data.AppSettings
import com.blueridge.parkwaynav.data.SavedRoute
import com.blueridge.parkwaynav.data.SavedStop
import com.blueridge.parkwaynav.di.AppContainer
import com.blueridge.parkwaynav.nav.ActiveRoute
import com.blueridge.parkwaynav.places.Suggestion
import com.blueridge.parkwaynav.routing.BrpRoute
import com.blueridge.parkwaynav.routing.routeMultiStop
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

data class PlannerStop(
    val id: String = UUID.randomUUID().toString(),
    val query: String = "",
    val resolved: LatLng? = null,
    val useCurrentLocation: Boolean = false
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val container = AppContainer.get(app)
    val brp = container.brp
    private val router = container.router
    private val directions = container.directions
    private val places = container.placesHelper
    private val routesRepo = container.routesRepo
    private val settingsRepo = container.settingsRepo
    private val backupManager = container.backupManager
    private val locationEngine = container.locationEngine

    val settings: StateFlow<AppSettings> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val savedRoutes: StateFlow<List<SavedRoute>> = routesRepo.routes

    // --- Planner state ------------------------------------------------------------------
    private val _stops = MutableStateFlow(
        listOf(
            PlannerStop(useCurrentLocation = true), // From: current location by default
            PlannerStop()                           // To
        )
    )
    val stops: StateFlow<List<PlannerStop>> = _stops.asStateFlow()

    private val _suggestions = MutableStateFlow<List<Suggestion>>(emptyList())
    val suggestions: StateFlow<List<Suggestion>> = _suggestions.asStateFlow()

    private val _activeField = MutableStateFlow(-1)
    val activeField: StateFlow<Int> = _activeField.asStateFlow()

    private val _computedRoute = MutableStateFlow<BrpRoute?>(null)
    val computedRoute: StateFlow<BrpRoute?> = _computedRoute.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }

    // Road-snapped polyline of the entire Parkway for the "show entire Parkway" overview.
    // Starts as the coarse centerline and is replaced with an accurate, road-following line.
    private val _parkwayOverview = MutableStateFlow(brp.polyline)
    val parkwayOverview: StateFlow<List<LatLng>> = _parkwayOverview.asStateFlow()
    private var overviewLoaded = false

    fun loadParkwayOverview() {
        if (overviewLoaded) return
        overviewLoaded = true
        viewModelScope.launch {
            val coarse = brp.polyline
            val snapped = directions.snapAlong(coarse)
            // A real road-snapped line has many more vertices than the coarse anchors.
            if (snapped.size > coarse.size + 5) {
                _parkwayOverview.value = snapped
                _message.value = "Parkway line: live road data"
            } else {
                _parkwayOverview.value = coarse
                val why = directions.lastStatus.ifBlank { "no response" }
                _message.value = "Parkway line approximate — Directions: $why"
                overviewLoaded = false // allow a retry after fixing the key/billing
            }
        }
    }

    private var autocompleteJob: Job? = null

    init {
        places.newSession()
        // Mirror language + PiP into a SharedPreferences cache for synchronous reads
        // (attachBaseContext locale, onUserLeaveHint PiP) that cannot await DataStore.
        viewModelScope.launch {
            settingsRepo.settings.collect { s ->
                getApplication<Application>()
                    .getSharedPreferences("brp_locale", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putString("language", s.language)
                    .putBoolean("pip_enabled", s.pipEnabled)
                    .apply()
            }
        }
    }

    // --- Settings -----------------------------------------------------------------------
    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            settingsRepo.update(transform)
            // Auto-backup mirrors changes to the chosen folder if enabled.
            backupManager.maybeAutoBackup(locationEnabled = settings.value.locationWasEnabled)
        }
    }

    // --- Planner editing ----------------------------------------------------------------
    fun setActiveField(index: Int) { _activeField.value = index; _suggestions.value = emptyList() }

    fun onQueryChanged(index: Int, text: String) {
        _stops.value = _stops.value.toMutableList().also {
            it[index] = it[index].copy(query = text, resolved = null, useCurrentLocation = false)
        }
        _activeField.value = index
        autocompleteJob?.cancel()
        if (text.length < 2) { _suggestions.value = emptyList(); return }
        autocompleteJob = viewModelScope.launch {
            delay(220) // debounce
            _suggestions.value = places.autocomplete(text)
        }
    }

    fun pickSuggestion(index: Int, suggestion: Suggestion) {
        viewModelScope.launch {
            val latLng = places.resolve(suggestion)
            _stops.value = _stops.value.toMutableList().also {
                it[index] = it[index].copy(
                    query = suggestion.primaryText,
                    resolved = latLng,
                    useCurrentLocation = false
                )
            }
            _suggestions.value = emptyList()
            _activeField.value = -1
            places.newSession()
        }
    }

    fun useCurrentLocation(index: Int) {
        _stops.value = _stops.value.toMutableList().also {
            it[index] = it[index].copy(
                query = "",
                resolved = null,
                useCurrentLocation = true
            )
        }
        _suggestions.value = emptyList()
        _activeField.value = -1
    }

    fun addStop() {
        _stops.value = _stops.value + PlannerStop()
    }

    fun removeStop(index: Int) {
        if (_stops.value.size <= 2) return
        _stops.value = _stops.value.toMutableList().also { it.removeAt(index) }
    }

    /** Routes from the user's current location to the closest Parkway access junction. */
    fun routeToNearestEntrance(onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val here = locationEngine.lastLocation()?.let { LatLng(it.latitude, it.longitude) }
            if (here == null) {
                _message.value = "Enable location to find the nearest entrance."
                onDone(false); return@launch
            }
            val nearest = brp.junctions.minByOrNull {
                com.blueridge.parkwaynav.routing.GeoUtils.distance(it.latLng, here)
            }
            if (nearest == null) { onDone(false); return@launch }
            _stops.value = listOf(
                PlannerStop(useCurrentLocation = true),
                PlannerStop(query = "${nearest.name} (entrance)", resolved = nearest.latLng)
            )
            computeRoute(onDone)
        }
    }

    /** Sets a tapped map point (overlook/junction) as the destination. */
    fun setDestinationPoint(label: String, latLng: LatLng) {
        val list = _stops.value.toMutableList()
        val dest = PlannerStop(query = label, resolved = latLng, useCurrentLocation = false)
        if (list.size >= 2) list[list.size - 1] = dest else list.add(dest)
        _stops.value = list
    }

    fun markOnboarded() { updateSettings { it.copy(onboarded = true) } }

    fun pickPoiAsDestination(poi: com.blueridge.parkwaynav.data.Poi) {
        val list = _stops.value.toMutableList()
        val dest = PlannerStop(
            query = poi.name,
            resolved = poi.latLng,
            useCurrentLocation = false
        )
        if (list.size >= 2) list[list.size - 1] = dest else list.add(dest)
        _stops.value = list
    }

    fun loadSavedRoute(route: SavedRoute) {
        _stops.value = route.stops.map {
            PlannerStop(query = it.label, resolved = it.latLng, useCurrentLocation = false)
        }
    }

    /** Best-known current location, or null if unavailable / no permission. */
    suspend fun currentLatLng(): LatLng? =
        locationEngine.lastLocation()?.let { LatLng(it.latitude, it.longitude) }

    // --- Routing ------------------------------------------------------------------------
    suspend fun resolveStopsToLatLng(): List<LatLng>? {
        val result = mutableListOf<LatLng>()
        for (stop in _stops.value) {
            val ll = when {
                stop.useCurrentLocation -> locationEngine.lastLocation()?.let { LatLng(it.latitude, it.longitude) }
                else -> stop.resolved
            } ?: return null
            result.add(ll)
        }
        return if (result.size >= 2) result else null
    }

    fun computeRoute(onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _busy.value = true
            val pts = resolveStopsToLatLng()
            if (pts == null) {
                _message.value = "Please set a start and destination (and enable location for current position)."
                _busy.value = false
                onDone(false)
                return@launch
            }
            val route = router.routeMultiStop(pts, preferParkway = true)
            _computedRoute.value = route
            ActiveRoute.set(route)
            _busy.value = false
            onDone(route != null)
        }
    }

    // --- Saving routes ------------------------------------------------------------------
    fun saveCurrentRoute(name: String) {
        val savedStops = _stops.value.map {
            SavedStop(
                label = if (it.useCurrentLocation) "Current location" else it.query.ifBlank { "Stop" },
                lat = it.resolved?.latitude ?: 0.0,
                lng = it.resolved?.longitude ?: 0.0
            )
        }
        routesRepo.save(SavedRoute(name = name.ifBlank { "My Parkway route" }, stops = savedStops))
        _message.value = "Route saved"
    }

    fun deleteRoute(id: String) = routesRepo.delete(id)

    // --- Backup / restore ---------------------------------------------------------------
    fun backupToFolder(folderUri: Uri, locationEnabled: Boolean) {
        viewModelScope.launch {
            val ok = backupManager.backupToFolder(folderUri, locationEnabled)
            updateSettings { it.copy(backupFolderUri = folderUri.toString()) }
            _message.value = if (ok) "Backup saved" else "Backup failed"
        }
    }

    fun backupToFile(fileUri: Uri, locationEnabled: Boolean) {
        viewModelScope.launch {
            val ok = backupManager.backupToFile(fileUri, locationEnabled)
            _message.value = if (ok) "Backup saved" else "Backup failed"
        }
    }

    fun restoreFromFile(fileUri: Uri, onRestored: (Boolean, Boolean) -> Unit) {
        viewModelScope.launch {
            val bundle = backupManager.restoreFromFile(fileUri)
            _message.value = if (bundle != null) "Settings restored" else "Restore failed"
            onRestored(bundle != null, bundle?.settings?.locationWasEnabled ?: false)
        }
    }
}
