// SPDX-License-Identifier: GPL-3.0-or-later
package be.fritkot.compass

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.asin
import kotlin.math.pow

/** Great-circle distance and bearing calculations. */
object GeoUtils {

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /** Haversine distance between two points, in metres. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)

        val a = sin(dPhi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(dLambda / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    /**
     * Initial great-circle bearing from point 1 to point 2, in degrees,
     * normalised to the range [0, 360).
     */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lon2 - lon1)

        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        val theta = atan2(y, x)
        return (Math.toDegrees(theta) + 360.0) % 360.0
    }

    /**
     * Formats a distance in metres as either "NNN m" (below 1 km) or "N.N km".
     * [Double.NaN] is used as the "unknown yet" sentinel (e.g. a fritkot
     * list shown before a location fix arrives) and renders as "—".
     */
    fun formatDistance(meters: Double): String {
        return when {
            meters.isNaN() -> "—"
            meters < 1000 -> "${meters.toInt()} m"
            else -> String.format("%.1f km", meters / 1000.0)
        }
    }
}
