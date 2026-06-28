package com.blueridge.parkwaynav.ui.planner

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
fun RoutePlannerScreen(app: MainViewModel, nav: NavController) {
    val context = LocalContext.current
    val stops by app.stops.collectAsState()
    val suggestions by app.suggestions.collectAsState()
    val activeField by app.activeField.collectAsState()
    val busy by app.busy.collectAsState()

    var showSaveDialog by remember { mutableStateOf(false) }
    var routeName by remember { mutableStateOf("") }

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.route_planner)) },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(stringResource(R.string.stops))
            Spacer(Modifier.height(8.dp))
            stops.forEachIndexed { index, stop ->
                Row(verticalAlignment = Alignment.Top) {
                    AddressField(
                        value = stop.query,
                        hint = if (index == 0) stringResource(R.string.from_hint)
                        else stringResource(R.string.to_hint),
                        isCurrentLocation = stop.useCurrentLocation,
                        isActive = activeField == index,
                        suggestions = if (activeField == index) suggestions else emptyList(),
                        onFocus = { app.setActiveField(index) },
                        onQueryChange = { app.onQueryChanged(index, it) },
                        onPickSuggestion = { app.pickSuggestion(index, it) },
                        onUseCurrentLocation = {
                            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            app.useCurrentLocation(index)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    if (stops.size > 2) {
                        IconButton(onClick = { app.removeStop(index) }) {
                            Icon(Icons.Filled.Close, contentDescription = null)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            OutlinedButton(onClick = { app.addStop() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("  " + stringResource(R.string.add_stop))
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    app.computeRoute { ok -> if (ok) nav.navigate(Routes.PREVIEW) }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.start_navigation)) }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showSaveDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.save_route)) }
        }
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text(stringResource(R.string.save_route)) },
            text = {
                OutlinedTextField(
                    value = routeName,
                    onValueChange = { routeName = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.route_name_hint)) }
                )
            },
            confirmButton = {
                Column {
                    TextButton(onClick = {
                        app.saveCurrentRoute(routeName)
                        showSaveDialog = false
                    }) { Text(stringResource(R.string.save_route)) }
                    TextButton(onClick = {
                        app.saveCurrentRoute(routeName)
                        // Find the just-saved route (most recent) and pin it.
                        app.savedRoutes.value.lastOrNull()?.let {
                            ShortcutUtil.pinRoute(context, it.id, it.name)
                        }
                        showSaveDialog = false
                    }) { Text(stringResource(R.string.save_to_home_screen)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
