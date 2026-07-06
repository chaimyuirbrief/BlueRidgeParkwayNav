package com.blueridge.parkwaynav.data

import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * A Blue Ridge Parkway road closure/alert. [fromMile]/[toMile] are parsed from the alert text
 * when present so the closure can be drawn on the Parkway centerline; if absent it's list-only.
 */
data class Closure(
    val title: String,
    val description: String,
    val category: String,
    val fromMile: Double?,
    val toMile: Double?,
    val url: String
) {
    val isSegment: Boolean get() = fromMile != null && toMile != null && toMile != fromMile
    val isPoint: Boolean get() = fromMile != null && (toMile == null || toMile == fromMile)
}

/**
 * Fetches live road closures/alerts for the Blue Ridge Parkway from the National Park Service
 * Data API (developer.nps.gov, parkCode=blri) at runtime on the device. Mileposts mentioned in
 * the alert text are mapped onto the centerline so closures render on the map.
 *
 * Requires a free NPS API key (BuildConfig.NPS_API_KEY). Degrades to empty if absent/offline.
 */
class ClosuresRepository(
    private val npsApiKey: String,
    private val brp: BrpRepository
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _closures = MutableStateFlow<List<Closure>>(emptyList())
    val closures: StateFlow<List<Closure>> = _closures.asStateFlow()

    @Volatile var lastStatus: String = ""
        private set

    private val mileRegex =
        Regex("""(?:milepost|mile\s*post|mp)\.?\s*(\d{1,3}(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (npsApiKey.isBlank()) { lastStatus = "No NPS API key"; return@withContext }
        try {
            val url =
                "https://developer.nps.gov/api/v1/alerts?parkCode=blri&limit=100&api_key=$npsApiKey"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000; readTimeout = 12_000; requestMethod = "GET"
            }
            val code = conn.responseCode
            if (code != 200) {
                lastStatus = "NPS HTTP $code"
                return@withContext
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return@withContext
            val list = data.mapNotNull { el ->
                val o = el.jsonObject
                val title = o["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val desc = o["description"]?.jsonPrimitive?.content ?: ""
                val category = o["category"]?.jsonPrimitive?.content ?: ""
                val link = o["url"]?.jsonPrimitive?.content ?: ""
                val text = "$title $desc"
                val isClosure = text.contains("clos", ignoreCase = true) ||
                    category.contains("clos", ignoreCase = true)
                if (!isClosure) return@mapNotNull null
                val miles = mileRegex.findAll(text).mapNotNull { it.groupValues[1].toDoubleOrNull() }
                    .filter { it in brp.minMile..brp.maxMile }.toList()
                val from = miles.getOrNull(0)
                val to = miles.getOrNull(1)
                Closure(title, desc, category, from, to, link)
            }
            _closures.value = list
            lastStatus = "OK (${list.size})"
        } catch (e: Exception) {
            lastStatus = "EXCEPTION: ${e.javaClass.simpleName}"
        }
    }

    /** Centerline points for a segment closure, or null for non-locatable closures. */
    fun segmentFor(c: Closure): List<LatLng>? = when {
        c.isSegment -> brp.segmentPoints(c.fromMile!!, c.toMile!!)
        else -> null
    }

    fun pointFor(c: Closure): LatLng? = c.fromMile?.let { brp.pointAtMile(it) }
}
