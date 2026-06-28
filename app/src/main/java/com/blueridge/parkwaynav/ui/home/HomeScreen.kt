package com.blueridge.parkwaynav.ui.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.blueridge.parkwaynav.R
import com.blueridge.parkwaynav.ui.MainViewModel
import com.blueridge.parkwaynav.ui.Routes
import com.blueridge.parkwaynav.ui.components.AddressField
import com.blueridge.parkwaynav.util.ShortcutUtil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(app: MainViewModel, nav: NavController) {
    val context = LocalContext.current
    val stops by app.stops.collectAsState()
    val suggestions by app.suggestions.collectAsState()
    val activeField by app.activeField.collectAsState()
    val savedRoutes by app.savedRoutes.collectAsState()
    val busy by app.busy.collectAsState()

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result handled implicitly by location reads */ }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
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
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // From (defaults to current location) and To, both with autocomplete.
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
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    app.computeRoute { ok -> if (ok) nav.navigate(Routes.NAVIGATION) }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Navigation, contentDescription = null)
                Spacer(Modifier.height(0.dp))
                Text("  " + stringResource(R.string.search_route))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { nav.navigate(Routes.PLANNER) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("  " + stringResource(R.string.plan_multi_stop))
            }

            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.saved_routes), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (savedRoutes.isEmpty()) {
                Text(
                    stringResource(R.string.no_saved_routes),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(savedRoutes) { route ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
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
                                IconButton(onClick = {
                                    ShortcutUtil.pinRoute(context, route.id, route.name)
                                }) {
                                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.save_to_home_screen))
                                }
                                IconButton(onClick = {
                                    app.loadSavedRoute(route)
                                    app.computeRoute { ok -> if (ok) nav.navigate(Routes.NAVIGATION) }
                                }) {
                                    Icon(Icons.Filled.Navigation, contentDescription = stringResource(R.string.start_navigation))
                                }
                                IconButton(onClick = { app.deleteRoute(route.id) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = null)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
