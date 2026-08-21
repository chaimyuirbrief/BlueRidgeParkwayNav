package com.blueridge.parkwaynav.ui.pois

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.blueridge.parkwaynav.R
import com.blueridge.parkwaynav.data.Poi
import com.blueridge.parkwaynav.ui.MainViewModel
import com.blueridge.parkwaynav.ui.Routes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlooksScreen(app: MainViewModel, nav: NavController) {
    val pois = app.brp.pois
    val types = remember { listOf("all") + pois.map { it.type }.distinct().sorted() }
    var filter by remember { mutableStateOf("all") }
    val filtered = if (filter == "all") pois else pois.filter { it.type == filter }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.overlooks_and_pois)) },
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
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                types.forEach { t ->
                    FilterChip(
                        selected = filter == t,
                        onClick = { filter = t },
                        label = { Text(prettyType(t)) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
            Text(
                stringResource(R.string.about_data_source),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp)
            ) {
                items(filtered) { poi ->
                    PoiCard(poi) {
                        // Set this POI as the destination and start a Parkway route.
                        app.useCurrentLocation(0)
                        app.pickPoiAsDestination(poi)
                        app.computeRoute { ok -> if (ok) nav.navigate(Routes.PREVIEW) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun PoiCard(poi: Poi, onNavigate: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onNavigate)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(poi.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    "MP ${poi.mile} • ${prettyType(poi.type)}" +
                        if (poi.isOffParkway) " • ${poi.spurMiles} mi off Parkway" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                if (poi.desc.isNotBlank()) {
                    Text(
                        poi.desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onNavigate) {
                Icon(Icons.Filled.Navigation, contentDescription = null)
            }
        }
    }
}

private fun prettyType(type: String): String = when (type) {
    "all" -> "All"
    "visitor_center" -> "Visitor centers"
    "overlook" -> "Overlooks"
    "campground" -> "Campgrounds"
    "picnic_area" -> "Picnic areas"
    "trailhead" -> "Trailheads"
    "recreation_area" -> "Recreation"
    "tunnel" -> "Tunnels"
    "lodge" -> "Lodges"
    "attraction" -> "Attractions"
    else -> type.replaceFirstChar { it.uppercase() }
}
