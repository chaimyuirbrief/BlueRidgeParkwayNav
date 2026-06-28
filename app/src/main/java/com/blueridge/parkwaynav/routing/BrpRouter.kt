package com.blueridge.parkwaynav.routing

import com.blueridge.parkwaynav.data.BrpRepository
import com.blueridge.parkwaynav.data.Junction
import com.google.android.gms.maps.model.LatLng
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class RouteStep(
    val instruction: String,
    val distanceMiles: Double,
    val maneuver: String = "",
    val onParkway: Boolean = false
)

data class BrpRoute(
    val polyline: List<LatLng>,
    val steps: List<RouteStep>,
    val totalMiles: Double,
    val parkwayMiles: Double,
    val entryJunction: Junction?,
    val exitJunction: Junction?,
    val origin: LatLng,
    val destination: LatLng
)

/**
 * The heart of the app: a router that keeps the traveler on the Blue Ridge Parkway as long
 * as possible, only directing them off at the access junction nearest their destination —
 * the opposite of a general map app, which leaves the Parkway at the first faster road.
 *
 * Strategy per origin→destination pair:
 *  - "on Parkway" means within [ON_PARKWAY_MILES] of the centerline.
 *  - origin on, destination on  → travel the centerline the whole way.
 *  - origin on, destination off → ride the Parkway to the junction closest to the
 *    destination, then take normal roads (Directions API) for the final leg.
 *  - origin off, destination on → normal roads to the nearest entry junction, then Parkway.
 *  - both off                   → only insert a Parkway segment if it is a meaningful part
 *    of the trip, otherwise fall back to a plain Directions route.
 */
class BrpRouter(
    private val repo: BrpRepository,
    private val directions: DirectionsService
) {
    suspend fun route(
        origin: LatLng,
        destination: LatLng,
        preferParkway: Boolean = true
    ): BrpRoute {
        val oOff = repo.offParkwayMiles(origin) ?: Double.MAX_VALUE
        val dOff = repo.offParkwayMiles(destination) ?: Double.MAX_VALUE
        val originOn = oOff <= ON_PARKWAY_MILES
        val destOn = dOff <= ON_PARKWAY_MILES

        if (!preferParkway || repo.polyline.size < 2) {
            return plainRoute(origin, destination)
        }

        return when {
            originOn && destOn -> parkwayOnly(origin, destination)
            originOn && !destOn -> originOnDestOff(origin, destination)
            !originOn && destOn -> originOffDestOn(origin, destination)
            else -> bothOff(origin, destination)
        }
    }

    // --- Case: both ends on the Parkway -------------------------------------------------
    private suspend fun parkwayOnly(origin: LatLng, destination: LatLng): BrpRoute {
        val mO = repo.mileFor(origin) ?: 0.0
        val mD = repo.mileFor(destination) ?: 0.0
        val seg = parkwaySegment(mO, mD)
        val miles = abs(mD - mO)
        val steps = listOf(
            RouteStep(
                instruction = "Continue on the Blue Ridge Parkway to milepost ${fmtMile(mD)}",
                distanceMiles = miles,
                maneuver = "straight",
                onParkway = true
            ),
            RouteStep("Arrive at your destination", 0.0, "arrive")
        )
        val poly = listOf(origin) + seg + listOf(destination)
        return BrpRoute(poly, steps, miles, miles, null, null, origin, destination)
    }

    // --- Case: start on Parkway, destination off ----------------------------------------
    private suspend fun originOnDestOff(origin: LatLng, destination: LatLng): BrpRoute {
        val mO = repo.mileFor(origin) ?: 0.0
        val exit = nearestJunctionTo(destination) ?: return plainRoute(origin, destination)
        val seg = parkwaySegment(mO, exit.mile)
        val parkwayMiles = abs(exit.mile - mO)
        val tail = directions.route(exit.latLng, destination)

        val steps = mutableListOf(
            RouteStep(
                "Continue on the Blue Ridge Parkway to ${exit.name} (MP ${fmtMile(exit.mile)})",
                parkwayMiles, "straight", onParkway = true
            ),
            RouteStep("Exit the Parkway at ${exit.name} onto ${exit.highway}", 0.0, "ramp")
        )
        tail.steps.forEach { steps.add(RouteStep(it.instruction, it.distanceMiles, it.maneuver)) }
        steps.add(RouteStep("Arrive at your destination", 0.0, "arrive"))

        val poly = listOf(origin) + seg + tail.polyline
        val total = parkwayMiles + tail.distanceMiles
        return BrpRoute(poly, steps, total, parkwayMiles, null, exit, origin, destination)
    }

    // --- Case: start off Parkway, destination on ----------------------------------------
    private suspend fun originOffDestOn(origin: LatLng, destination: LatLng): BrpRoute {
        val mD = repo.mileFor(destination) ?: 0.0
        val entry = nearestJunctionTo(origin) ?: return plainRoute(origin, destination)
        val head = directions.route(origin, entry.latLng)
        val seg = parkwaySegment(entry.mile, mD)
        val parkwayMiles = abs(mD - entry.mile)

        val steps = mutableListOf<RouteStep>()
        head.steps.forEach { steps.add(RouteStep(it.instruction, it.distanceMiles, it.maneuver)) }
        steps.add(RouteStep("Enter the Blue Ridge Parkway at ${entry.name}", 0.0, "merge", onParkway = true))
        steps.add(
            RouteStep(
                "Continue on the Blue Ridge Parkway to milepost ${fmtMile(mD)}",
                parkwayMiles, "straight", onParkway = true
            )
        )
        steps.add(RouteStep("Arrive at your destination", 0.0, "arrive"))

        val poly = head.polyline + seg + listOf(destination)
        val total = head.distanceMiles + parkwayMiles
        return BrpRoute(poly, steps, total, parkwayMiles, entry, null, origin, destination)
    }

    // --- Case: both off the Parkway -----------------------------------------------------
    private suspend fun bothOff(origin: LatLng, destination: LatLng): BrpRoute {
        val entry = nearestJunctionTo(origin)
        val exit = nearestJunctionTo(destination)
        if (entry == null || exit == null) return plainRoute(origin, destination)
        val parkwayMiles = abs(exit.mile - entry.mile)
        // Only force a Parkway segment if it is a meaningful chunk of the journey.
        if (parkwayMiles < MIN_PARKWAY_SEGMENT_MILES) return plainRoute(origin, destination)

        val head = directions.route(origin, entry.latLng)
        val seg = parkwaySegment(entry.mile, exit.mile)
        val tail = directions.route(exit.latLng, destination)

        val steps = mutableListOf<RouteStep>()
        head.steps.forEach { steps.add(RouteStep(it.instruction, it.distanceMiles, it.maneuver)) }
        steps.add(RouteStep("Enter the Blue Ridge Parkway at ${entry.name}", 0.0, "merge", onParkway = true))
        steps.add(
            RouteStep(
                "Continue on the Blue Ridge Parkway to ${exit.name} (MP ${fmtMile(exit.mile)})",
                parkwayMiles, "straight", onParkway = true
            )
        )
        steps.add(RouteStep("Exit the Parkway at ${exit.name} onto ${exit.highway}", 0.0, "ramp"))
        tail.steps.forEach { steps.add(RouteStep(it.instruction, it.distanceMiles, it.maneuver)) }
        steps.add(RouteStep("Arrive at your destination", 0.0, "arrive"))

        val poly = head.polyline + seg + tail.polyline
        val total = head.distanceMiles + parkwayMiles + tail.distanceMiles
        return BrpRoute(poly, steps, total, parkwayMiles, entry, exit, origin, destination)
    }

    // --- Fallback: ordinary point-to-point route ----------------------------------------
    private suspend fun plainRoute(origin: LatLng, destination: LatLng): BrpRoute {
        val dir = directions.route(origin, destination)
        val steps = dir.steps.map { RouteStep(it.instruction, it.distanceMiles, it.maneuver) } +
            RouteStep("Arrive at your destination", 0.0, "arrive")
        return BrpRoute(dir.polyline, steps, dir.distanceMiles, 0.0, null, null, origin, destination)
    }

    /**
     * Builds the Parkway polyline between two mileposts (handles either direction) and snaps it
     * onto the real road via the Directions API so the drawn line follows the actual curves.
     */
    private suspend fun parkwaySegment(fromMile: Double, toMile: Double): List<LatLng> {
        val lo = min(fromMile, toMile)
        val hi = max(fromMile, toMile)
        val pts = mutableListOf<LatLng>()
        pts.add(repo.pointAtMile(fromMile))
        val between = repo.centerline.filter { it.mile in lo..hi }.map { it.latLng }
        if (fromMile <= toMile) pts.addAll(between) else pts.addAll(between.reversed())
        pts.add(repo.pointAtMile(toMile))
        return directions.snapAlong(pts)
    }

    /** Junction whose straight-line distance to [target] is smallest (the best exit/entry). */
    private fun nearestJunctionTo(target: LatLng): Junction? =
        repo.junctions.minByOrNull { GeoUtils.distance(it.latLng, target) }

    private fun fmtMile(m: Double): String =
        if (m == m.toLong().toDouble()) m.toLong().toString() else String.format("%.1f", m)

    companion object {
        const val ON_PARKWAY_MILES = 0.4
        const val MIN_PARKWAY_SEGMENT_MILES = 3.0
    }
}

/** Routes a multi-stop trip by chaining the Parkway router across consecutive waypoints. */
suspend fun BrpRouter.routeMultiStop(
    stops: List<LatLng>,
    preferParkway: Boolean = true
): BrpRoute? {
    if (stops.size < 2) return null
    val legs = (0 until stops.size - 1).map { i -> route(stops[i], stops[i + 1], preferParkway) }
    val poly = legs.flatMap { it.polyline }
    val steps = legs.flatMapIndexed { i, leg ->
        if (i == legs.size - 1) leg.steps
        else leg.steps.dropLast(1) + RouteStep("Stop ${i + 1} reached — continuing", 0.0, "waypoint")
    }
    return BrpRoute(
        polyline = poly,
        steps = steps,
        totalMiles = legs.sumOf { it.totalMiles },
        parkwayMiles = legs.sumOf { it.parkwayMiles },
        entryJunction = legs.first().entryJunction,
        exitJunction = legs.last().exitJunction,
        origin = stops.first(),
        destination = stops.last()
    )
}
