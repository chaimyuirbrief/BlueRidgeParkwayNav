package com.blueridge.parkwaynav.ui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.blueridge.parkwaynav.R
import com.blueridge.parkwaynav.routing.BrpRoute
import com.blueridge.parkwaynav.ui.MainViewModel
import com.blueridge.parkwaynav.ui.Routes
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import kotlin.math.roundToInt

@Composable
fun RoutePreviewScreen(app: MainViewModel, nav: NavController) {
    val route by app.computedRoute.collectAsState()

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            route?.origin ?: LatLng(36.1, -81.5), 8f
        )
    }

    // Fit the whole route into view.
    LaunchedEffect(route) {
        val r = route ?: return@LaunchedEffect
        if (r.polyline.size >= 2) {
            val b = LatLngBounds.Builder()
            r.polyline.forEach { b.include(it) }
            runCatching {
                cameraPositionState.animate(
                    CameraUpdateFactory.newLatLngBounds(b.build(), 140), 700
                )
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(mapType = MapType.TERRAIN),
            uiSettings = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = false)
        ) {
            route?.let { r ->
                Polyline(points = r.polyline, color = MaterialTheme.colorScheme.primary, width = 14f)
                Marker(state = MarkerState(position = r.origin), title = "Start")
                Marker(state = MarkerState(position = r.destination), title = "Destination")
            }
        }

        // Back button (top-left)
        androidx.compose.material3.FilledTonalIconButton(
            onClick = { nav.popBackStack() },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
        ) {
            Icon(Icons.Filled.ArrowBack, contentDescription = null)
        }

        // Bottom summary + steps + start
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            val r = route
            if (r == null) {
                Text(
                    stringResource(R.string.no_route),
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.route_preview), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Metric(stringResource(R.string.total_distance), "${fmt(r.totalMiles)} mi")
                        Metric(stringResource(R.string.on_parkway_distance), "${fmt(r.parkwayMiles)} mi")
                        Metric(stringResource(R.string.est_time), estTime(r))
                    }
                    r.exitJunction?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.exits_at, it.name),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Divider()
                    Text(
                        stringResource(R.string.directions_steps),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(160.dp)) {
                        items(r.steps) { step ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(
                                    step.instruction,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                if (step.distanceMiles > 0) {
                                    Text(
                                        "${fmt(step.distanceMiles)} mi",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Divider()
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { nav.navigate(Routes.NAVIGATION) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Navigation, contentDescription = null)
                        Text("  " + stringResource(R.string.start_navigation))
                    }
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun fmt(miles: Double): String =
    if (miles >= 10) miles.roundToInt().toString() else String.format("%.1f", miles)

/** Rough ETA: the Parkway is slow (≈45 mph limit); other roads ≈50 mph. */
private fun estTime(r: BrpRoute): String {
    val offParkway = (r.totalMiles - r.parkwayMiles).coerceAtLeast(0.0)
    val hours = r.parkwayMiles / 45.0 + offParkway / 50.0
    val totalMin = (hours * 60).roundToInt()
    return if (totalMin >= 60) "${totalMin / 60}h ${totalMin % 60}m" else "${totalMin}m"
}
