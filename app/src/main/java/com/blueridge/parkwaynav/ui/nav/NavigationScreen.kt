package com.blueridge.parkwaynav.ui.nav

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.blueridge.parkwaynav.R
import com.blueridge.parkwaynav.data.AppearanceMode
import com.blueridge.parkwaynav.ui.MainViewModel
import com.blueridge.parkwaynav.ui.NavigationViewModel
import com.blueridge.parkwaynav.ui.settings.ChoiceRow
import com.blueridge.parkwaynav.ui.settings.SliderRow
import com.blueridge.parkwaynav.ui.settings.SwitchRow
import com.blueridge.parkwaynav.util.MapBitmaps
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationScreen(app: MainViewModel, nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm: NavigationViewModel = viewModel()
    val route by vm.route.collectAsState()
    val ui by vm.ui.collectAsState()
    val voiceOn by vm.voiceEnabled.collectAsState()
    val showSpeed by vm.showSpeed.collectAsState()
    val settings by app.settings.collectAsState()

    var following by remember { mutableStateOf(true) }
    var northUp by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    val locationPermission = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.onLocationPermissionGranted() }

    DisposableEffect(Unit) {
        vm.start()
        onDispose { vm.stop() }
    }
    LaunchedEffect(Unit) {
        if (!vm.hasLocationPermission()) {
            locationPermission.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(route?.origin ?: LatLng(36.0949, -81.8125), 14f)
    }

    // If the user drags the map, stop auto-following until they tap re-center.
    LaunchedEffect(cameraPositionState.isMoving) {
        if (cameraPositionState.isMoving &&
            cameraPositionState.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE
        ) {
            following = false
        }
    }

    // Follow the driver while in follow mode: heading-up + tilt, or north-up + flat.
    LaunchedEffect(ui.location, ui.bearing, northUp, following) {
        if (!following) return@LaunchedEffect
        val loc = ui.location ?: return@LaunchedEffect
        cameraPositionState.animate(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(loc)
                    .zoom(17f)
                    .tilt(if (northUp) 0f else 55f)
                    .bearing(if (northUp) 0f else ui.bearing)
                    .build()
            ),
            700
        )
    }

    val arrowIcon = remember { MapBitmaps.fromVector(context, R.drawable.ic_nav_arrow, 54) }

    Box(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(mapType = MapType.TERRAIN),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = false,
                compassEnabled = false,
                myLocationButtonEnabled = false,
                rotationGesturesEnabled = true,
                scrollGesturesEnabled = true
            )
        ) {
            route?.let { r ->
                // Dark casing under a bright line for high contrast against terrain.
                Polyline(points = r.polyline, color = Color(0xFF06281C), width = 30f, zIndex = 1f)
                Polyline(points = r.polyline, color = Color(0xFF00E5FF), width = 16f, zIndex = 2f)
            }
            ui.location?.let { loc ->
                Marker(
                    state = MarkerState(position = loc),
                    icon = arrowIcon,
                    rotation = ui.bearing,
                    flat = true,
                    anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                    zIndex = 3f
                )
            }
        }

        // Top instruction banner
        route?.let {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(start = 64.dp, end = 12.dp, top = 12.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        if (ui.arrived) stringResource(R.string.arriving)
                        else ui.currentStep?.instruction ?: stringResource(R.string.continue_on_parkway),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    if (ui.nextStep != null && ui.distanceToStepMiles > 0) {
                        Text(
                            "Then in ${formatMiles(ui.distanceToStepMiles)}: ${ui.nextStep?.instruction.orEmpty()}",
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        // Hamburger menu (top-left) — settings without leaving navigation.
        SmallFloatingActionButton(
            onClick = { showMenu = true },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
        ) {
            Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.menu))
        }

        // Bottom info row: milepost + ZIP (left), speed (right)
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            InfoChip(
                label = stringResource(R.string.mile_marker),
                value = ui.milepost,
                sub = if (ui.zip.isNotBlank()) "${stringResource(R.string.zip_code)} ${ui.zip}" else null
            )
            if (showSpeed) {
                InfoChip(
                    label = stringResource(R.string.current_speed),
                    value = ui.speedMph.toString(),
                    sub = stringResource(R.string.speed_unit_mph)
                )
            }
        }

        // Right-side control stack
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FloatingActionButton(onClick = { vm.toggleVoice() }) {
                Icon(
                    if (voiceOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                    contentDescription = stringResource(R.string.toggle_voice)
                )
            }
            FloatingActionButton(onClick = { vm.toggleShowSpeed() }) {
                Text("MPH", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            // North-up / heading-up toggle
            FloatingActionButton(onClick = { northUp = !northUp }) {
                Icon(
                    Icons.Filled.Explore,
                    contentDescription = if (northUp) stringResource(R.string.north_up) else stringResource(R.string.heading_up)
                )
            }
            // Full-route overview (zoom out to whole route); pan freely, then re-center.
            FloatingActionButton(onClick = {
                following = false
                route?.let { r ->
                    if (r.polyline.size >= 2) {
                        val b = LatLngBounds.Builder()
                        r.polyline.forEach { b.include(it) }
                        scope.launch {
                            runCatching {
                                cameraPositionState.animate(
                                    CameraUpdateFactory.newLatLngBounds(b.build(), 120), 700
                                )
                            }
                        }
                    }
                }
            }) {
                Icon(Icons.Filled.Route, contentDescription = stringResource(R.string.full_route))
            }
            // Re-center / resume following
            FloatingActionButton(onClick = { following = true }) {
                Icon(Icons.Filled.MyLocation, contentDescription = stringResource(R.string.recenter))
            }
            FloatingActionButton(
                onClick = { vm.stop(); nav.popBackStack() },
                containerColor = MaterialTheme.colorScheme.errorContainer
            ) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.end_navigation))
            }
        }
    }

    // Quick settings — change settings without ending navigation.
    if (showMenu) {
        ModalBottomSheet(
            onDismissRequest = { showMenu = false },
            sheetState = rememberModalBottomSheetState()
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.quick_settings),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                )
                SwitchRow(
                    title = stringResource(R.string.toggle_voice),
                    checked = settings.voiceEnabled,
                    onChange = { v -> app.updateSettings { it.copy(voiceEnabled = v) } }
                )
                SliderRow(
                    title = stringResource(R.string.speaking_speed),
                    value = settings.speakingSpeed,
                    valueRange = 0.5f..2.0f,
                    valueLabel = "${"%.1f".format(settings.speakingSpeed)}×",
                    onChange = { v -> app.updateSettings { it.copy(speakingSpeed = v) } }
                )
                ChoiceRow(
                    title = stringResource(R.string.appearance),
                    options = listOf(
                        AppearanceMode.LIGHT to stringResource(R.string.appearance_light),
                        AppearanceMode.DARK to stringResource(R.string.appearance_dark),
                        AppearanceMode.SYSTEM to stringResource(R.string.appearance_system)
                    ),
                    selected = settings.appearance,
                    onSelect = { m -> app.updateSettings { it.copy(appearance = m) } }
                )
                SwitchRow(
                    title = stringResource(R.string.keep_screen_on),
                    checked = settings.keepScreenOn,
                    onChange = { v -> app.updateSettings { it.copy(keepScreenOn = v) } }
                )
                SwitchRow(
                    title = stringResource(R.string.run_in_background),
                    checked = settings.runInBackground,
                    onChange = { v -> app.updateSettings { it.copy(runInBackground = v) } }
                )
                SwitchRow(
                    title = stringResource(R.string.pip_mode),
                    checked = settings.pipEnabled,
                    onChange = { v -> app.updateSettings { it.copy(pipEnabled = v) } }
                )
                Spacer(Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
private fun InfoChip(label: String, value: String, sub: String?) {
    Surface(color = Color(0xCC0F1A14), shape = RoundedCornerShape(12.dp)) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, color = Color(0xFFB7E4C7), fontSize = 11.sp)
            Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            sub?.let { Text(it, color = Color(0xFFB7E4C7), fontSize = 11.sp) }
        }
    }
}

private fun formatMiles(miles: Double): String =
    if (miles >= 1) String.format("%.1f mi", miles) else "${(miles * 5280).toInt()} ft"
