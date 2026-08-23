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
 */
data class Fritkot(
    val id: Long,
    val name: String?,
    val lat: Double,
    val lon: Double,
    val address: String = ""
)

/** A fritkot paired with its computed distance (metres) and bearing (degrees, 0-360) from the user. */
data class FritkotWithBearing(
    val fritkot: Fritkot,
    val distanceMeters: Double,
    val bearingDegrees: Double
)
