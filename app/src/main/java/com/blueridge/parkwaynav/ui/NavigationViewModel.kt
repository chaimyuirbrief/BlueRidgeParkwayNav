package com.blueridge.parkwaynav.ui

import android.app.Application
import android.location.Geocoder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blueridge.parkwaynav.di.AppContainer
import com.blueridge.parkwaynav.nav.ActiveRoute
import com.blueridge.parkwaynav.nav.NavigationService
import com.blueridge.parkwaynav.nav.TtsManager
import com.blueridge.parkwaynav.routing.BrpRoute
import com.blueridge.parkwaynav.routing.GeoUtils
import com.blueridge.parkwaynav.routing.RouteStep
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class NavUiState(
    val location: LatLng? = null,
    val bearing: Float = 0f,
    val speedMph: Int = 0,
    val milepost: String = "—",
    val onParkway: Boolean = false,
    val zip: String = "",
    val currentStep: RouteStep? = null,
    val nextStep: RouteStep? = null,
    val distanceToStepMiles: Double = 0.0,
    val remainingMiles: Double = 0.0,
    val arrived: Boolean = false,
    val needsLocationPermission: Boolean = false
)

class NavigationViewModel(app: Application) : AndroidViewModel(app) {

    private val container = AppContainer.get(app)
    private val brp = container.brp
    private val locationEngine = container.locationEngine
    private val settingsRepo = container.settingsRepo
    private val tts = TtsManager(app)

    val route: StateFlow<BrpRoute?> = ActiveRoute.current

    private val _ui = MutableStateFlow(NavUiState())
    val ui: StateFlow<NavUiState> = _ui.asStateFlow()

    // On-screen toggles, seeded from settings.
    private val _voiceEnabled = MutableStateFlow(true)
    val voiceEnabled: StateFlow<Boolean> = _voiceEnabled.asStateFlow()
    private val _showSpeed = MutableStateFlow(true)
    val showSpeed: StateFlow<Boolean> = _showSpeed.asStateFlow()

    val settings = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var locationJob: Job? = null
    private var spokenStepIndex = -1
    private var spokenApproachIndex = -1
    private var lastZipQueryAt = 0L
    private var cumulativeStepMiles: List<Double> = emptyList()
    private var routePolyline: List<LatLng> = emptyList()

    fun start() {
        val r = route.value ?: return
        routePolyline = r.polyline
        cumulativeStepMiles = buildCumulative(r.steps)
        viewModelScope.launch {
            // Keep TTS in sync with the user's voice/sound settings for the whole session.
            settingsRepo.settings.collect { st ->
                tts.enabled = st.voiceEnabled
                tts.speakingSpeed = st.speakingSpeed
                tts.voiceType = st.voiceType
                tts.playOverBluetooth = st.playOverBluetooth
                tts.playDuringCall = st.playDuringCall
                tts.languageTag = st.language
                _voiceEnabled.value = st.voiceEnabled
            }
        }
        tts.init {
            if (_voiceEnabled.value) tts.speak("Starting navigation on the Blue Ridge Parkway.")
        }
        startLocationIfPermitted()
    }

    fun hasLocationPermission(): Boolean {
        val ctx = getApplication<Application>()
        return androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** Starts the foreground service + location updates, but only when location is granted. */
    fun startLocationIfPermitted() {
        if (!hasLocationPermission()) {
            _ui.value = _ui.value.copy(needsLocationPermission = true)
            return
        }
        _ui.value = _ui.value.copy(needsLocationPermission = false)
        if (settings.value?.runInBackground != false) {
            runCatching { NavigationService.start(getApplication()) }
        }
        startLocation()
    }

    /** Call after the user grants location at runtime. */
    fun onLocationPermissionGranted() = startLocationIfPermitted()

    private fun startLocation() {
        if (!hasLocationPermission()) return
        locationJob?.cancel()
        locationJob = viewModelScope.launch {
            locationEngine.locationUpdates(1500L)
                .catch { /* ignore location errors (e.g. permission revoked mid-trip) */ }
                .collect { loc ->
                val here = LatLng(loc.latitude, loc.longitude)
                val speedMph = (loc.speed * 2.2369363).toInt().coerceAtLeast(0)
                val bearing = if (loc.hasBearing()) loc.bearing else _ui.value.bearing

                val off = brp.offParkwayMiles(here) ?: Double.MAX_VALUE
                val onParkway = off <= 0.5
                val milepost = if (onParkway) fmtMile(brp.mileFor(here) ?: 0.0) else "Off Parkway"

                updateProgress(here)
                maybeUpdateZip(loc.latitude, loc.longitude)

                _ui.value = _ui.value.copy(
                    location = here,
                    bearing = bearing,
                    speedMph = speedMph,
                    onParkway = onParkway,
                    milepost = milepost
                )
            }
        }
    }

    private fun updateProgress(here: LatLng) {
        val r = route.value ?: return
        if (routePolyline.size < 2) return
        val snap = GeoUtils.snapToPolyline(here, routePolyline) ?: return
        val along = distanceAlong(routePolyline, snap.segmentIndex, snap.fraction)
        val total = cumulativeStepMiles.lastOrNull() ?: 0.0
        val remaining = (total - along).coerceAtLeast(0.0)

        val stepIndex = cumulativeStepMiles.indexOfFirst { it > along + 1e-6 }
            .let { if (it < 0) r.steps.size - 1 else it }
        val step = r.steps.getOrNull(stepIndex)
        val next = r.steps.getOrNull(stepIndex + 1)
        val distToStep = ((cumulativeStepMiles.getOrNull(stepIndex) ?: along) - along).coerceAtLeast(0.0)

        // Speak the step when first entered.
        if (stepIndex != spokenStepIndex && step != null) {
            spokenStepIndex = stepIndex
            spokenApproachIndex = -1
            if (_voiceEnabled.value) tts.speak(step.instruction, flush = true)
        }
        // "In half a mile…" approach prompt for the upcoming maneuver.
        if (next != null && distToStep in 0.05..0.6 && spokenApproachIndex != stepIndex) {
            spokenApproachIndex = stepIndex
            if (_voiceEnabled.value) tts.speak("In ${fmtDist(distToStep)}, ${next.instruction}")
        }

        val arrived = remaining < 0.03
        if (arrived && !_ui.value.arrived && _voiceEnabled.value) {
            tts.speak("You have arrived at your destination.", flush = true)
        }
        _ui.value = _ui.value.copy(
            currentStep = step,
            nextStep = next,
            distanceToStepMiles = distToStep,
            remainingMiles = remaining,
            arrived = arrived
        )
    }

    private fun maybeUpdateZip(lat: Double, lng: Double) {
        val now = System.currentTimeMillis()
        if (now - lastZipQueryAt < 12_000 && _ui.value.zip.isNotBlank()) return
        lastZipQueryAt = now
        viewModelScope.launch(Dispatchers.IO) {
            val zip = runCatching {
                @Suppress("DEPRECATION")
                Geocoder(getApplication(), Locale.getDefault())
                    .getFromLocation(lat, lng, 1)
                    ?.firstOrNull()?.postalCode
            }.getOrNull()
            if (!zip.isNullOrBlank()) {
                withContext(Dispatchers.Main) { _ui.value = _ui.value.copy(zip = zip) }
            }
        }
    }

    fun toggleVoice() {
        val v = !_voiceEnabled.value
        _voiceEnabled.value = v
        tts.enabled = v
        if (!v) tts.stop()
        viewModelScope.launch { settingsRepo.update { it.copy(voiceEnabled = v) } }
    }

    fun toggleShowSpeed() { _showSpeed.value = !_showSpeed.value }

    fun stop() {
        locationJob?.cancel()
        tts.stop()
        tts.shutdown()
        NavigationService.stop(getApplication())
        ActiveRoute.clear()
    }

    override fun onCleared() {
        super.onCleared()
        locationJob?.cancel()
        tts.shutdown()
        NavigationService.stop(getApplication())
    }

    // --- helpers ------------------------------------------------------------------------
    private fun buildCumulative(steps: List<RouteStep>): List<Double> {
        var acc = 0.0
        return steps.map { acc += it.distanceMiles; acc }
    }

    private fun distanceAlong(poly: List<LatLng>, segIndex: Int, fraction: Double): Double {
        var meters = 0.0
        for (i in 0 until segIndex) meters += GeoUtils.distance(poly[i], poly[i + 1])
        if (segIndex < poly.size - 1) {
            meters += GeoUtils.distance(poly[segIndex], poly[segIndex + 1]) * fraction
        }
        return GeoUtils.metersToMiles(meters)
    }

    private fun fmtMile(m: Double): String =
        if (m == m.toLong().toDouble()) m.toLong().toString() else String.format("%.1f", m)

    private fun fmtDist(miles: Double): String = when {
        miles >= 0.95 -> "${String.format("%.1f", miles)} miles"
        miles >= 0.18 -> "a quarter mile"
        else -> "${(miles * 5280).toInt()} feet"
    }
}
