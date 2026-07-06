package com.blueridge.parkwaynav.data

import android.content.Context
import com.google.android.gms.maps.model.LatLng
import com.blueridge.parkwaynav.routing.GeoUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CenterPoint(val mile: Double, val lat: Double, val lng: Double, val name: String = "") {
    val latLng: LatLng get() = LatLng(lat, lng)
}

@Serializable
data class Poi(
    val name: String,
    val mile: Double,
    val lat: Double,
    val lng: Double,
    val type: String,
    val desc: String = ""
) {
    val latLng: LatLng get() = LatLng(lat, lng)
}

@Serializable
data class Junction(
    val name: String,
    val mile: Double,
    val lat: Double,
    val lng: Double,
    val highway: String = ""
) {
    val latLng: LatLng get() = LatLng(lat, lng)
}

@Serializable
data class BrpDataset(
    val centerline: List<CenterPoint> = emptyList(),
    val pois: List<Poi> = emptyList(),
    val junctions: List<Junction> = emptyList()
)

/**
 * Loads and holds the static Blue Ridge Parkway dataset (centerline, points of interest,
 * and access junctions). Sourced from the National Park Service (nps.gov/blri).
 *
 * The centerline is an ordered polyline of anchor points keyed by milepost; it is the
 * backbone of the "stay on the Parkway" router. Higher-resolution NPS GIS data can be
 * dropped into assets/brp_data.json to improve fidelity without code changes.
 */
class BrpRepository private constructor(val dataset: BrpDataset) {

    val centerline: List<CenterPoint> = dataset.centerline.sortedBy { it.mile }
    val polyline: List<LatLng> = centerline.map { it.latLng }
    val pois: List<Poi> = dataset.pois.sortedBy { it.mile }
    val junctions: List<Junction> = dataset.junctions.sortedBy { it.mile }

    val minMile: Double get() = centerline.firstOrNull()?.mile ?: 0.0
    val maxMile: Double get() = centerline.lastOrNull()?.mile ?: 469.0

    /** Interpolated milepost for a snap result onto the centerline. */
    fun mileAt(segmentIndex: Int, fraction: Double): Double {
        if (centerline.isEmpty()) return 0.0
        if (segmentIndex >= centerline.size - 1) return centerline.last().mile
        val a = centerline[segmentIndex]
        val b = centerline[segmentIndex + 1]
        return a.mile + (b.mile - a.mile) * fraction
    }

    /** Snaps an arbitrary location to the Parkway centerline; null if no centerline loaded. */
    fun snap(point: LatLng): GeoUtils.Snap? = GeoUtils.snapToPolyline(point, polyline)

    /** Milepost for an arbitrary point (its nearest point on the Parkway). */
    fun mileFor(point: LatLng): Double? {
        val snap = snap(point) ?: return null
        return mileAt(snap.segmentIndex, snap.fraction)
    }

    /** How far (miles) a point is from the Parkway centerline. */
    fun offParkwayMiles(point: LatLng): Double? {
        val snap = snap(point) ?: return null
        return GeoUtils.metersToMiles(snap.distanceMeters)
    }

    /** The centerline point (interpolated) nearest to a milepost value. */
    fun pointAtMile(mile: Double): LatLng {
        if (centerline.isEmpty()) return LatLng(0.0, 0.0)
        if (mile <= centerline.first().mile) return centerline.first().latLng
        if (mile >= centerline.last().mile) return centerline.last().latLng
        for (i in 0 until centerline.size - 1) {
            val a = centerline[i]
            val b = centerline[i + 1]
            if (mile in a.mile..b.mile) {
                val t = if (b.mile == a.mile) 0.0 else (mile - a.mile) / (b.mile - a.mile)
                return GeoUtils.lerp(a.latLng, b.latLng, t)
            }
        }
        return centerline.last().latLng
    }

    /** Centerline points between two mileposts (inclusive), for drawing a highlighted segment. */
    fun segmentPoints(fromMile: Double, toMile: Double): List<LatLng> {
        val lo = minOf(fromMile, toMile)
        val hi = maxOf(fromMile, toMile)
        val pts = mutableListOf(pointAtMile(lo))
        centerline.filter { it.mile in lo..hi }.forEach { pts.add(it.latLng) }
        pts.add(pointAtMile(hi))
        return pts
    }

    companion object {
        @Volatile private var instance: BrpRepository? = null

        fun get(context: Context): BrpRepository =
            instance ?: synchronized(this) {
                instance ?: load(context).also { instance = it }
            }

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private fun load(context: Context): BrpRepository {
            val text = context.assets.open("brp_data.json").bufferedReader().use { it.readText() }
            val dataset = json.decodeFromString(BrpDataset.serializer(), text)
            return BrpRepository(dataset)
        }
    }
}
