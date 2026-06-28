package com.blueridge.parkwaynav.routing

import com.google.android.gms.maps.model.LatLng
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Geometry helpers used by the Parkway router. All distances in meters unless noted. */
object GeoUtils {

    const val EARTH_RADIUS_M = 6_371_000.0
    const val METERS_PER_MILE = 1609.344

    fun metersToMiles(m: Double): Double = m / METERS_PER_MILE

    /** Great-circle distance in meters. */
    fun distance(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Initial bearing in degrees (0-360) from a to b. */
    fun bearing(a: LatLng, b: LatLng): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Linear interpolation between two coordinates (planar approximation, fine for short spans). */
    fun lerp(a: LatLng, b: LatLng, t: Double): LatLng =
        LatLng(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)

    /**
     * Projects point [p] onto the segment [a]-[b] using a local equirectangular approximation.
     * Returns the fraction along the segment (clamped 0..1) of the closest point.
     */
    fun projectFraction(p: LatLng, a: LatLng, b: LatLng): Double {
        // Convert to local planar meters around `a`.
        val latRef = Math.toRadians(a.latitude)
        fun x(ll: LatLng) = Math.toRadians(ll.longitude - a.longitude) * cos(latRef) * EARTH_RADIUS_M
        fun y(ll: LatLng) = Math.toRadians(ll.latitude - a.latitude) * EARTH_RADIUS_M
        val ax = 0.0; val ay = 0.0
        val bx = x(b); val by = y(b)
        val px = x(p); val py = y(p)
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return 0.0
        val t = ((px - ax) * dx + (py - ay) * dy) / len2
        return max(0.0, min(1.0, t))
    }

    data class Snap(
        val point: LatLng,
        val segmentIndex: Int,
        val fraction: Double,
        val distanceMeters: Double
    )

    /**
     * Finds the closest point on a polyline to [p].
     * Returns null for an empty polyline.
     */
    fun snapToPolyline(p: LatLng, polyline: List<LatLng>): Snap? {
        if (polyline.isEmpty()) return null
        if (polyline.size == 1) return Snap(polyline[0], 0, 0.0, distance(p, polyline[0]))
        var best: Snap? = null
        for (i in 0 until polyline.size - 1) {
            val a = polyline[i]
            val b = polyline[i + 1]
            val t = projectFraction(p, a, b)
            val proj = lerp(a, b, t)
            val d = distance(p, proj)
            if (best == null || d < best!!.distanceMeters) {
                best = Snap(proj, i, t, d)
            }
        }
        return best
    }
}
