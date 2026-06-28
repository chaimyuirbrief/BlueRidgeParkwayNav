package com.blueridge.parkwaynav.routing

import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A single guidance step for the off-Parkway portions of a route. */
data class DirStep(
    val instruction: String,
    val distanceMiles: Double,
    val maneuver: String,
    val polyline: List<LatLng>
)

data class DirResult(
    val polyline: List<LatLng>,
    val steps: List<DirStep>,
    val distanceMiles: Double
)

/**
 * Thin client for the Google Directions REST API, used only for the legs that leave the
 * Parkway (origin → entry junction and exit junction → destination). The Parkway portion
 * itself is built directly from the NPS centerline so it never gets "optimized" off the road.
 *
 * If the network/API is unavailable it degrades to a straight-line connector so the app
 * still produces a usable route.
 */
class DirectionsService(private val apiKey: String) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Snaps an ordered list of on-road points onto the actual road network, returning a dense
     * polyline that follows real curves. Used to turn the coarse Parkway centerline anchors into
     * an accurate line. Falls back to the input points if no API key / the request fails, so the
     * caller always gets a usable polyline.
     */
    suspend fun snapAlong(points: List<LatLng>): List<LatLng> {
        if (points.size < 2 || apiKey.isBlank()) return points
        val out = ArrayList<LatLng>()
        var i = 0
        // Use SMALL chunks (origin + a few waypoints): with closely-spaced waypoints the road
        // follower can't wander far between them. Directions allows up to 23 waypoints, but fewer
        // keeps the path tight to the Parkway.
        val chunk = 6
        while (i < points.size - 1) {
            val end = minOf(i + chunk, points.size - 1)
            val chunkPoints = points.subList(i, end + 1)
            val waypoints = points.subList(i + 1, end)
            val result = routeVia(points[i], points[end], waypoints)

            // Guard against the road follower taking a faster highway detour between sparse
            // anchors: if the returned distance is far longer than the straight anchor path,
            // keep the straight anchor line for this chunk instead of a bonkers loop.
            val straightMiles = (0 until chunkPoints.size - 1)
                .sumOf { GeoUtils.metersToMiles(GeoUtils.distance(chunkPoints[it], chunkPoints[it + 1])) }
            val seg = if (result.distanceMiles > 0 && result.distanceMiles > 1.8 * straightMiles + 1.0) {
                chunkPoints // detour rejected
            } else {
                result.polyline.ifEmpty { chunkPoints }
            }
            if (out.isEmpty()) out.addAll(seg) else out.addAll(seg.drop(1))
            i = end
        }
        return if (out.size >= 2) out else points
    }

    suspend fun routeVia(
        origin: LatLng,
        destination: LatLng,
        waypoints: List<LatLng>
    ): DirResult = withContext(Dispatchers.IO) {
        val fallback = DirResult(listOf(origin) + waypoints + destination, emptyList(), 0.0)
        if (apiKey.isBlank()) return@withContext fallback
        try {
            val url = buildString {
                append("https://maps.googleapis.com/maps/api/directions/json")
                append("?origin=${origin.latitude},${origin.longitude}")
                append("&destination=${destination.latitude},${destination.longitude}")
                if (waypoints.isNotEmpty()) {
                    append("&waypoints=")
                    append(waypoints.joinToString("%7C") { "${it.latitude},${it.longitude}" })
                }
                // Avoid interstates/freeways so the Parkway line can't hop onto a parallel highway.
                append("&avoid=highways%7Cferries")
                append("&mode=driving")
                append("&key=${URLEncoder.encode(apiKey, "UTF-8")}")
            }
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000; readTimeout = 12_000; requestMethod = "GET"
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parse(body) ?: fallback
        } catch (e: Exception) {
            fallback
        }
    }

    suspend fun route(origin: LatLng, destination: LatLng): DirResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext straightLine(origin, destination)
        try {
            val url = buildString {
                append("https://maps.googleapis.com/maps/api/directions/json")
                append("?origin=${origin.latitude},${origin.longitude}")
                append("&destination=${destination.latitude},${destination.longitude}")
                append("&mode=driving")
                append("&key=${URLEncoder.encode(apiKey, "UTF-8")}")
            }
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 12_000
                requestMethod = "GET"
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parse(body) ?: straightLine(origin, destination)
        } catch (e: Exception) {
            straightLine(origin, destination)
        }
    }

    private fun parse(body: String): DirResult? {
        val root = json.parseToJsonElement(body).jsonObject
        val routes = root["routes"]?.jsonArray ?: return null
        if (routes.isEmpty()) return null
        val route = routes[0].jsonObject
        val overview = route["overview_polyline"]?.jsonObject?.get("points")?.jsonPrimitive?.content
        val legs = route["legs"]?.jsonArray ?: return null
        if (legs.isEmpty()) return null
        val leg = legs[0].jsonObject
        var totalMeters = 0.0
        val steps = mutableListOf<DirStep>()
        leg["steps"]?.jsonArray?.forEach { s ->
            val obj = s.jsonObject
            val meters = obj["distance"]?.jsonObject?.get("value")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            totalMeters += meters
            val html = obj["html_instructions"]?.jsonPrimitive?.content ?: ""
            val maneuver = obj["maneuver"]?.jsonPrimitive?.content ?: ""
            val pts = obj["polyline"]?.jsonObject?.get("points")?.jsonPrimitive?.content
            steps.add(
                DirStep(
                    instruction = stripHtml(html),
                    distanceMiles = GeoUtils.metersToMiles(meters),
                    maneuver = maneuver,
                    polyline = pts?.let { decodePolyline(it) } ?: emptyList()
                )
            )
        }
        val poly = overview?.let { decodePolyline(it) } ?: steps.flatMap { it.polyline }
        return DirResult(poly, steps, GeoUtils.metersToMiles(totalMeters))
    }

    private fun straightLine(origin: LatLng, destination: LatLng): DirResult {
        val miles = GeoUtils.metersToMiles(GeoUtils.distance(origin, destination))
        return DirResult(
            polyline = listOf(origin, destination),
            steps = listOf(
                DirStep("Head toward your destination", miles, "straight", listOf(origin, destination))
            ),
            distanceMiles = miles
        )
    }

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()

    companion object {
        /** Decodes a Google encoded polyline string into a list of LatLng. */
        fun decodePolyline(encoded: String): List<LatLng> {
            val poly = ArrayList<LatLng>()
            var index = 0
            val len = encoded.length
            var lat = 0
            var lng = 0
            while (index < len) {
                var b: Int
                var shift = 0
                var result = 0
                do {
                    b = encoded[index++].code - 63
                    result = result or (b and 0x1f shl shift)
                    shift += 5
                } while (b >= 0x20)
                val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
                lat += dlat
                shift = 0
                result = 0
                do {
                    b = encoded[index++].code - 63
                    result = result or (b and 0x1f shl shift)
                    shift += 5
                } while (b >= 0x20)
                val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
                lng += dlng
                poly.add(LatLng(lat / 1E5, lng / 1E5))
            }
            return poly
        }
    }
}
