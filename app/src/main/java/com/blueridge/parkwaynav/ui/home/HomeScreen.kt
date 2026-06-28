package com.blueridge.parkwaynav.ui.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.blueridge.parkwaynav.R
import com.blueridge.parkwaynav.ui.MainViewModel
import com.blueridge.parkwaynav.ui.Routes
import com.blueridge.parkwaynav.ui.components.AddressField
import com.blueridge.parkwaynav.util.ShortcutUtil
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
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
fun HomeScreen(app: MainViewModel, nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stops by app.stops.collectAsState()
    val suggestions by app.suggestions.collectAsState()
    val activeField by app.activeField.collectAsState()
    val savedRoutes by app.savedRoutes.collectAsState()
    val busy by app.busy.collectAsState()
    val parkwayOverview by app.parkwayOverview.collectAsState()
    val settings by app.settings.collectAsState()

    var hasLocation by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasLocation = granted }

    var showParkway by remember { mutableStateOf(false) }
    var showSavedSheet by remember { mutableStateOf(false) }

    // The whole Parkway, as a bounding box, for the "show entire Parkway" overview.
    val parkwayBounds = remember {
        val b = LatLngBounds.Builder()
        app.brp.polyline.forEach { b.include(it) }
        runCatching { b.build() }.getOrNull()
    }

    val cameraPositionState = rememberCameraPositionState {
        // Open centered on the Parkway region so the map is immediately useful.
        position = CameraPosition.fromLatLngZoom(LatLng(36.7, -81.3), 6.6f)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ---- The map IS the home screen ----
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                mapType = MapType.TERRAIN,
                isMyLocationEnabled = hasLocation
            ),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = false,
                myLocationButtonEnabled = false,
                compassEnabled = true,
                mapToolbarEnabled = false
            )
        ) {
            if (showParkway) {
                Polyline(
                    points = parkwayOverview,
                    color = MaterialTheme.colorScheme.tertiary,
                    width = 12f
                )
            }
            // All access junctions (blue) and overlooks/attractions (orange/green) shown
            // while browsing. Tap a marker's info window to route there.
            app.brp.junctions.forEach { j ->
                Marker(
                    state = MarkerState(position = j.latLng),
                    title = j.name,
                    snippet = "MP ${j.mile} • ${j.highway}",
                    icon = com.google.android.gms.maps.model.BitmapDescriptorFactory
                        .defaultMarker(com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_AZURE),
                    onInfoWindowClick = {
                        app.useCurrentLocation(0)
                        app.setDestinationPoint(j.name, j.latLng)
                        app.computeRoute { ok -> if (ok) nav.navigate(Routes.PREVIEW) }
                    }
                )
            }
            app.brp.pois.forEach { p ->
                val hue = if (p.type == "overlook")
                    com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_ORANGE
                else com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_GREEN
                Marker(
                    state = MarkerState(position = p.latLng),
                    title = p.name,
                    snippet = "MP ${p.mile} • ${p.type.replace('_', ' ')}",
                    icon = com.google.android.gms.maps.model.BitmapDescriptorFactory.defaultMarker(hue),
                    onInfoWindowClick = {
                        app.useCurrentLocation(0)
                        app.setDestinationPoint(p.name, p.latLng)
                        app.computeRoute { ok -> if (ok) nav.navigate(Routes.PREVIEW) }
                    }
                )
            }
        }

        // ---- Top overlay: search + quick actions ----
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { nav.navigate(Routes.OVERLOOKS) }) {
                            Icon(Icons.Filled.Map, contentDescription = stringResource(R.string.overlooks_and_pois))
                        }
                        IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                        }
                        IconButton(onClick = { nav.navigate(Routes.ABOUT) }) {
                            Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.about))
                        }
                    }
                    AddressField(
                        value = stops.getOrNull(0)?.query ?: "",
                        hint = stringResource(R.string.from_hint),
                        isCurrentLocation = stops.getOrNull(0)?.useCurrentLocation == true,
                        isActive = activeField == 0,
                        suggestions = if (activeField == 0) suggestions else emptyList(),
                        onFocus = { app.setActiveField(0) },
                        onQueryChange = { app.onQueryChanged(0, it) },
                        onPickSuggestion = { app.pickSuggestion(0, it) },
                        onUseCurrentLocation = {
                            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            app.useCurrentLocation(0)
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    AddressField(
                        value = stops.getOrNull(1)?.query ?: "",
                        hint = stringResource(R.string.to_hint),
                        isCurrentLocation = stops.getOrNull(1)?.useCurrentLocation == true,
                        isActive = activeField == 1,
                        suggestions = if (activeField == 1) suggestions else emptyList(),
                        onFocus = { app.setActiveField(1) },
                        onQueryChange = { app.onQueryChanged(1, it) },
                        onPickSuggestion = { app.pickSuggestion(1, it) },
                        onUseCurrentLocation = {
                            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            app.useCurrentLocation(1)
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    Row {
                        Button(
                            onClick = {
                                locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                app.computeRoute { ok -> if (ok) nav.navigate(Routes.PREVIEW) }
                            },
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Navigation, contentDescription = null)
                            Text("  " + stringResource(R.string.search_route))
                        }
                        Spacer(Modifier.padding(4.dp))
                        OutlinedButton(onClick = { nav.navigate(Routes.PLANNER) }) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.plan_multi_stop))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            app.routeToNearestEntrance { ok -> if (ok) nav.navigate(Routes.PREVIEW) }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Explore, contentDescription = null)
                        Text("  " + stringResource(R.string.nearest_entrance))
                    }
                }
            }
        }

        // ---- Bottom-right controls ----
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ExtendedFloatingActionButton(
                onClick = {
                    showParkway = !showParkway
                    if (showParkway) app.loadParkwayOverview()
                    if (showParkway && parkwayBounds != null) {
                        scope.launch {
                            runCatching {
                                cameraPositionState.animate(
                                    CameraUpdateFactory.newLatLngBounds(parkwayBounds, 120), 900
                                )
                            }
                        }
                    }
                },
                icon = { Icon(Icons.Filled.Route, contentDescription = null) },
                text = {
                    Text(
                        if (showParkway) stringResource(R.string.hide_full_parkway)
                        else stringResource(R.string.show_full_parkway)
                    )
                }
            )
            FloatingActionButton(onClick = {
                locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                scope.launch {
                    app.currentLatLng()?.let {
                        cameraPositionState.animate(
                            CameraUpdateFactory.newLatLngZoom(it, 14f), 800
                        )
                    }
                }
            }) {
                Icon(Icons.Filled.MyLocation, contentDescription = stringResource(R.string.my_location))
            }
        }

        // ---- Bottom-left: saved routes ----
        if (savedRoutes.isNotEmpty()) {
            ExtendedFloatingActionButton(
                onClick = { showSavedSheet = true },
                icon = { Icon(Icons.Filled.Bookmark, contentDescription = null) },
                text = { Text(stringResource(R.string.saved_routes)) },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
            )
        }
    }

    if (showSavedSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSavedSheet = false },
            sheetState = rememberModalBottomSheetState()
        ) {
            Text(
                stringResource(R.string.saved_routes),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                items(savedRoutes) { route ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(route.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    route.stops.joinToString(" → ") { it.label },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { ShortcutUtil.pinRoute(context, route.id, route.name) }) {
                                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.save_to_home_screen))
                            }
                            IconButton(onClick = {
                                showSavedSheet = false
                                app.loadSavedRoute(route)
                                app.computeRoute { ok -> if (ok) nav.navigate(Routes.PREVIEW) }
                            }) {
                                Icon(Icons.Filled.Navigation, contentDescription = stringResource(R.string.start_navigation))
                            }
                            IconButton(onClick = { app.deleteRoute(route.id) }) {
                                Icon(Icons.Filled.Delete, contentDescription = null)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    // First-run onboarding (offline + privacy + location).
    if (!settings.onboarded) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.onboarding_title)) },
            text = {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(stringResource(R.string.onboarding_body))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    app.markOnboarded()
                    locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }) { Text(stringResource(R.string.onboarding_enable_location)) }
            },
            dismissButton = {
                TextButton(onClick = { app.markOnboarded() }) {
                    Text(stringResource(R.string.onboarding_got_it))
                }
            }
        )
    }
}
