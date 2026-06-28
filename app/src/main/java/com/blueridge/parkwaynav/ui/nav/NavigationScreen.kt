package com.blueridge.parkwaynav.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.blueridge.parkwaynav.R
import com.blueridge.parkwaynav.ui.NavigationViewModel
import com.blueridge.parkwaynav.util.MapBitmaps
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState

@Composable
fun NavigationScreen(nav: NavController) {
    val context = LocalContext.current
    val vm: NavigationViewModel = viewModel()
    val route by vm.route.collectAsState()
    val ui by vm.ui.collectAsState()
    val voiceOn by vm.voiceEnabled.collectAsState()
    val showSpeed by vm.showSpeed.collectAsState()

    val locationPermission = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.onLocationPermissionGranted() }

    DisposableEffect(Unit) {
        vm.start()
        onDispose { vm.stop() }
    }

    // Navigation needs location; request it on entry if not already granted.
    LaunchedEffect(Unit) {
        if (!vm.hasLocationPermission()) {
            locationPermission.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            route?.origin ?: LatLng(36.0949, -81.8125), 14f
        )
    }

    // Follow the driver: arrow up = direction of travel, map tilted for a forward view.
    LaunchedEffect(ui.location, ui.bearing) {
        val loc = ui.location ?: return@LaunchedEffect
        cameraPositionState.animate(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(loc)
                    .zoom(17f)
                    .tilt(55f)
                    .bearing(ui.bearing)
                    .build()
            ),
            800
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
                myLocationButtonEnabled = false
            )
        ) {
            route?.let { r ->
                Polyline(
                    points = r.polyline,
                    color = MaterialTheme.colorScheme.primary,
                    width = 16f
                )
            }
            ui.location?.let { loc ->
                Marker(
                    state = MarkerState(position = loc),
                    icon = arrowIcon,
                    rotation = ui.bearing,
                    flat = true,
                    anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                    zIndex = 2f
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
                    .padding(12.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        if (ui.arrived) stringResourceSafe(R.string.arriving)
                        else ui.currentStep?.instruction ?: stringResourceSafe(R.string.continue_on_parkway),
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
                label = stringResourceSafe(R.string.mile_marker),
                value = ui.milepost,
                sub = if (ui.zip.isNotBlank()) "${stringResourceSafe(R.string.zip_code)} ${ui.zip}" else null
            )
            if (showSpeed) {
                InfoChip(
                    label = stringResourceSafe(R.string.current_speed),
                    value = ui.speedMph.toString(),
                    sub = stringResourceSafe(R.string.speed_unit_mph)
                )
            }
        }

        // Right-side control stack: voice toggle, speed toggle, recenter, end.
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FloatingActionButton(onClick = { vm.toggleVoice() }) {
                Icon(
                    if (voiceOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                    contentDescription = stringResourceSafe(R.string.toggle_voice)
                )
            }
            FloatingActionButton(onClick = { vm.toggleShowSpeed() }) {
                Text("MPH", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            FloatingActionButton(onClick = {
                ui.location?.let {
                    cameraPositionState.move(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder().target(it).zoom(17f).tilt(55f)
                                .bearing(ui.bearing).build()
                        )
                    )
                }
            }) {
                Icon(Icons.Filled.MyLocation, contentDescription = stringResourceSafe(R.string.recenter))
            }
            FloatingActionButton(
                onClick = { vm.stop(); nav.popBackStack() },
                containerColor = MaterialTheme.colorScheme.errorContainer
            ) {
                Icon(Icons.Filled.Close, contentDescription = stringResourceSafe(R.string.end_navigation))
            }
        }
    }
}

@Composable
private fun InfoChip(label: String, value: String, sub: String?) {
    Surface(
        color = Color(0xCC0F1A14),
        shape = RoundedCornerShape(12.dp)
    ) {
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

@Composable
private fun stringResourceSafe(id: Int): String = androidx.compose.ui.res.stringResource(id)

private fun formatMiles(miles: Double): String =
    if (miles >= 1) String.format("%.1f mi", miles) else "${(miles * 5280).toInt()} ft"
