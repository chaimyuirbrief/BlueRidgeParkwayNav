package com.blueridge.parkwaynav.places

import android.content.Context
import com.blueridge.parkwaynav.data.BrpRepository
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.model.RectangularBounds
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** A unified suggestion: either a Parkway POI (resolved instantly) or a Places prediction. */
data class Suggestion(
    val primaryText: String,
    val secondaryText: String,
    val placeId: String? = null,      // null => local POI with coordinates
    val latLng: LatLng? = null,        // present for local POIs
    val isParkwayPoi: Boolean = false
)

/**
 * Provides address autocomplete for the From/To fields. Results combine:
 *  1. Matching Blue Ridge Parkway points of interest (instant, offline, ranked first), and
 *  2. Google Places predictions biased to the Parkway corridor.
 */
class PlacesHelper(context: Context, private val brp: BrpRepository) {

    private val client: PlacesClient? =
        if (Places.isInitialized()) Places.createClient(context) else null
    private var sessionToken: AutocompleteSessionToken? = null

    // Bias predictions to the Parkway corridor (VA ↔ NC).
    private val corridorBounds = RectangularBounds.newInstance(
        LatLng(35.0, -83.9),
        LatLng(38.4, -78.3)
    )

    fun newSession() {
        sessionToken = AutocompleteSessionToken.newInstance()
    }

    private fun localPoiMatches(query: String): List<Suggestion> {
        if (query.isBlank()) return emptyList()
        val q = query.lowercase()
        return brp.pois.filter { it.name.lowercase().contains(q) }
            .take(4)
            .map {
                Suggestion(
                    primaryText = it.name,
                    secondaryText = "Blue Ridge Parkway • MP ${it.mile}",
                    latLng = it.latLng,
                    isParkwayPoi = true
                )
            }
    }

    suspend fun autocomplete(query: String): List<Suggestion> {
        val local = localPoiMatches(query)
        val remote = remotePredictions(query)
        return local + remote
    }

    private suspend fun remotePredictions(query: String): List<Suggestion> {
        val c = client ?: return emptyList()
        if (query.isBlank()) return emptyList()
        if (sessionToken == null) newSession()
        val request = FindAutocompletePredictionsRequest.builder()
            .setLocationBias(corridorBounds)
            .setSessionToken(sessionToken)
            .setQuery(query)
            .build()
        return suspendCancellableCoroutine { cont ->
            c.findAutocompletePredictions(request)
                .addOnSuccessListener { response ->
                    cont.resume(response.autocompletePredictions.map { p ->
                        Suggestion(
                            primaryText = p.getPrimaryText(null).toString(),
                            secondaryText = p.getSecondaryText(null).toString(),
                            placeId = p.placeId
                        )
                    })
                }
                .addOnFailureListener { cont.resume(emptyList()) }
        }
    }

    /** Resolves a prediction's placeId to coordinates. */
    suspend fun resolve(suggestion: Suggestion): LatLng? {
        if (suggestion.latLng != null) return suggestion.latLng
        val c = client ?: return null
        val placeId = suggestion.placeId ?: return null
        val request = FetchPlaceRequest.builder(placeId, listOf(Place.Field.LAT_LNG)).build()
        return suspendCancellableCoroutine { cont ->
            c.fetchPlace(request)
                .addOnSuccessListener { cont.resume(it.place.latLng) }
                .addOnFailureListener { cont.resume(null) }
        }.also { sessionToken = null } // close billing session after a place fetch
    }

    @Suppress("unused")
    private fun bounds(): LatLngBounds =
        LatLngBounds(LatLng(35.0, -83.9), LatLng(38.4, -78.3))
}
