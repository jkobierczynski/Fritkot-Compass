// SPDX-License-Identifier: GPL-3.0-or-later
package be.fritkot.compass

/**
 * A single fritkot / frituur / friterie location.
 *
 * @param id stable id (OSM node id when it comes from Overpass, or a local
 *           index for the bundled offline sample set)
 * @param name display name, may be null if OSM has no `name` tag
 * @param lat latitude in degrees
 * @param lon longitude in degrees
 * @param address a short human readable address fragment, may be empty
 * @param openingHours the raw OSM `opening_hours` tag value, e.g.
 *        `"Mo-Fr 11:30-14:00,17:30-22:00; Sa,Su 11:30-22:00"`, or null if
 *        OSM has no such tag for this node (very common — treat as unknown,
 *        not as "always open"). See [OpeningHours] for how this is
 *        interpreted.
 */
data class Fritkot(
    val id: Long,
    val name: String?,
    val lat: Double,
    val lon: Double,
    val address: String = "",
    val openingHours: String? = null
)

/** A fritkot paired with its computed distance (metres) and bearing (degrees, 0-360) from the user. */
data class FritkotWithBearing(
    val fritkot: Fritkot,
    val distanceMeters: Double,
    val bearingDegrees: Double
)
