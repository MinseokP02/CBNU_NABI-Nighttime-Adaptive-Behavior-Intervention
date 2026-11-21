package com.cbnu.nabi.walk

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A point as [lat, lon] (the same order the server stores). */
typealias LatLon = DoubleArray

/** A recommended loop. [snapped] = follows real footpaths (routing server answered); otherwise straight lines. */
data class WalkRoute(
    val points: List<LatLon>,
    val waypoints: List<LatLon>,
    val distanceM: Double,
    val minutes: Int,
    val snapped: Boolean,
)

/**
 * Builds a walking loop that starts and ends at the user's position.
 *
 * - Default: an equilateral triangle start → A → B → start pointing towards [headingDeg], sized so
 *   the walk takes about [targetMinutes] at [SPEED_M_PER_MIN] (real paths detour, see [DETOUR]).
 * - With user-picked [via] points (map taps): start → via… → start.
 *
 * The points are snapped to footpaths by the public OSRM foot router (routing.openstreetmap.de).
 * Only the loop's corner points are sent there; if it can't be reached, straight lines are used.
 * Blocking: call from a background thread.
 */
object WalkRoutePlanner {
    const val SPEED_M_PER_MIN = 75.0 // 4.5 km/h
    private const val DETOUR = 1.25
    private const val ROUTER = "https://routing.openstreetmap.de/routed-foot/route/v1/foot/"

    fun plan(start: LatLon, targetMinutes: Int, headingDeg: Double, via: List<LatLon>): WalkRoute {
        val corners = if (via.isNotEmpty()) via else {
            val side = targetMinutes * SPEED_M_PER_MIN / 3.0 / DETOUR
            listOf(offset(start, headingDeg, side), offset(start, headingDeg + 60, side))
        }
        val stops = listOf(start) + corners + listOf(start)
        val first = runCatching { route(stops, corners) }.getOrNull() ?: return straight(stops, corners)
        // Real paths detour more or less than [DETOUR]: rescale the automatic loop once to hit the target time.
        if (via.isEmpty() && kotlin.math.abs(first.minutes - targetMinutes) > targetMinutes * 0.15) {
            val side = targetMinutes * SPEED_M_PER_MIN / 3.0 / DETOUR * targetMinutes / first.minutes
            val c2 = listOf(offset(start, headingDeg, side), offset(start, headingDeg + 60, side))
            runCatching { route(listOf(start) + c2 + listOf(start), c2) }.getOrNull()?.let { second ->
                if (kotlin.math.abs(second.minutes - targetMinutes) < kotlin.math.abs(first.minutes - targetMinutes)) return second
            }
        }
        return first
    }

    private fun route(stops: List<LatLon>, corners: List<LatLon>): WalkRoute? {
        val coords = stops.joinToString(";") { "%.6f,%.6f".format(java.util.Locale.US, it[1], it[0]) }
        val conn = URL("$ROUTER$coords?overview=full&geometries=geojson&steps=false").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 6_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "NABI-sleep-coach-android")
            if (conn.responseCode != 200) return null
            val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            if (j.optString("code") != "Ok") return null
            val r = j.getJSONArray("routes").getJSONObject(0)
            val line = r.getJSONObject("geometry").getJSONArray("coordinates")
            val pts = (0 until line.length()).map { val c = line.getJSONArray(it); doubleArrayOf(c.getDouble(1), c.getDouble(0)) }
            val dist = r.optDouble("distance", length(pts))
            return WalkRoute(pts, corners, dist, minutesFor(dist), snapped = true)
        } finally {
            conn.disconnect()
        }
    }

    private fun straight(stops: List<LatLon>, corners: List<LatLon>): WalkRoute {
        val dist = length(stops)
        return WalkRoute(stops, corners, dist, minutesFor(dist), snapped = false)
    }

    fun minutesFor(distanceM: Double) = Math.round(distanceM / SPEED_M_PER_MIN).toInt().coerceAtLeast(1)

    fun length(pts: List<LatLon>): Double = pts.zipWithNext { a, b -> haversine(a, b) }.sum()

    fun haversine(a: LatLon, b: LatLon): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b[0] - a[0])
        val dLon = Math.toRadians(b[1] - a[1])
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(a[0])) * cos(Math.toRadians(b[0])) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** The point [distM] metres from [p] towards [bearingDeg]. */
    fun offset(p: LatLon, bearingDeg: Double, distM: Double): LatLon {
        val r = 6_371_000.0
        val d = distM / r
        val br = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(p[0])
        val lon1 = Math.toRadians(p[1])
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(br))
        val lon2 = lon1 + atan2(sin(br) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return doubleArrayOf(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    /** Keeps at most [max] points (evenly spaced, ends kept) before sending a path to the server. */
    fun thin(pts: List<LatLon>, max: Int = 1500): List<LatLon> {
        if (pts.size <= max) return pts
        val step = (pts.size - 1).toDouble() / (max - 1)
        return (0 until max).map { pts[Math.round(it * step).toInt()] }
    }
}
