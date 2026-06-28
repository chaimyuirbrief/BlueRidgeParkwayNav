package com.blueridge.parkwaynav.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blueridge.parkwaynav.places.Suggestion

@Composable
fun AddressField(
    value: String,
    hint: String,
    isCurrentLocation: Boolean,
    isActive: Boolean,
    suggestions: List<Suggestion>,
    onFocus: () -> Unit,
    onQueryChange: (String) -> Unit,
    onPickSuggestion: (Suggestion) -> Unit,
    onUseCurrentLocation: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = if (isCurrentLocation) "" else value,
            onValueChange = { onQueryChange(it) },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusLike(onFocus),
            singleLine = true,
            placeholder = {
                Text(if (isCurrentLocation) "📍 Current location" else hint)
            },
            leadingIcon = {
                Icon(
                    if (isCurrentLocation) Icons.Filled.MyLocation else Icons.Filled.LocationOn,
                    contentDescription = null
                )
            },
            trailingIcon = {
                IconButton(onClick = onUseCurrentLocation) {
                    Icon(Icons.Filled.MyLocation, contentDescription = "Use current location")
                }
            }
        )
        if (isActive && suggestions.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .heightIn(max = 260.dp)
            ) {
                LazyColumn {
                    items(suggestions) { s ->
                        SuggestionRow(s) { onPickSuggestion(s) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(s: Suggestion, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Icon(
                if (s.isParkwayPoi) Icons.Filled.Place else Icons.Filled.LocationOn,
                contentDescription = null,
                tint = if (s.isParkwayPoi) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp)
            )
            Column {
                Text(
                    s.primaryText,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (s.secondaryText.isNotBlank()) {
                    Text(
                        s.secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** Small helper to trigger [onFocus] when this field gains focus. */
private fun Modifier.onFocusLike(onFocus: () -> Unit): Modifier =
    this.then(
        androidx.compose.ui.focus.onFocusChanged { state ->
            if (state.isFocused) onFocus()
        }
    )
