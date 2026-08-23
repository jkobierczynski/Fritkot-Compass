// SPDX-License-Identifier: GPL-3.0-or-later
package be.fritkot.compass

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), SensorEventListener, LocationListener {

    private lateinit var compassView: CompassView
    private lateinit var tvStatus: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvName: TextView
    private lateinit var tvAddress: TextView
    private lateinit var btnAction: Button
    private lateinit var llNearby: LinearLayout

    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private lateinit var overpassClient: OverpassClient

    private var rotationSensor: Sensor? = null
    private var hasRotationFix = false
    private var smoothedAzimuth = 0f

    private var lastQueryLocation: Location? = null
    private var isQueryInFlight = false
    private var nearby: List<FritkotWithBearing> = emptyList()

    // Which fritkot the compass/distance is tracking. null means "follow the
    // closest one automatically" (the default); once the user taps one of
    // the up to 5 nearest in the list, this pins to that one by id so the
    // app keeps pointing at it even if a different fritkot becomes closer.
    private var selectedFritkotId: Long? = null

    private val requestCode = 4242
    private val maxSelectable = 5

    // Re-query Overpass if the user has moved more than this many metres
    // since the last query, so the list stays relevant on a bike/car/on foot.
    private val requeryDistanceMeters = 300.0
    private val searchRadiusMeters = 30_000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        compassView = findViewById(R.id.compassView)
        tvStatus = findViewById(R.id.tvStatus)
        tvDistance = findViewById(R.id.tvDistance)
        tvName = findViewById(R.id.tvName)
        tvAddress = findViewById(R.id.tvAddress)
        btnAction = findViewById(R.id.btnAction)
        llNearby = findViewById(R.id.llNearby)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        overpassClient = OverpassClient(applicationContext)

        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationSensor == null) {
            tvStatus.text = getString(R.string.status_no_sensor)
        }

        btnAction.setOnClickListener {
            if (hasLocationPermission()) {
                startLocationUpdates()
            } else {
                requestLocationPermission()
            }
        }

        if (hasLocationPermission()) {
            startLocationUpdates()
        } else {
            requestLocationPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        if (hasLocationPermission()) startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)
    }

    // ---- Permissions ----------------------------------------------------

    private fun hasLocationPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        tvStatus.text = getString(R.string.status_permission_needed)
        btnAction.text = getString(R.string.grant_permission)
        btnAction.visibility = View.VISIBLE
        requestPermissions(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            requestCode
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == this.requestCode) {
            if (hasLocationPermission()) {
                btnAction.visibility = View.GONE
                startLocationUpdates()
            } else {
                Toast.makeText(this, getString(R.string.status_permission_needed), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---- Location ---------------------------------------------------------

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        tvStatus.text = getString(R.string.status_locating)

        val providers = locationManager.getProviders(true)
        var seeded = false
        for (provider in providers) {
            @Suppress("MissingPermission")
            val last = locationManager.getLastKnownLocation(provider)
            if (last != null && (!seeded || (lastQueryLocation != null && last.time > lastQueryLocation!!.time))) {
                onLocationChanged(last)
                seeded = true
            }
        }

        try {
            if (providers.contains(LocationManager.GPS_PROVIDER)) {
                @Suppress("MissingPermission")
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 4000L, 25f, this)
            }
            if (providers.contains(LocationManager.NETWORK_PROVIDER)) {
                @Suppress("MissingPermission")
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 4000L, 25f, this)
            }
        } catch (e: SecurityException) {
            // Permission revoked between the check above and this call; ask again.
            requestLocationPermission()
        }
    }

    override fun onLocationChanged(location: Location) {
        val previous = lastQueryLocation
        val moved = previous == null || previous.distanceTo(location) >= requeryDistanceMeters
        if (moved && !isQueryInFlight) {
            lastQueryLocation = location
            isQueryInFlight = true
            tvStatus.text = getString(R.string.status_searching)
            overpassClient.fetchNearby(
                location.latitude,
                location.longitude,
                searchRadiusMeters,
                object : OverpassClient.Callback {
                    override fun onResult(fritkots: List<Fritkot>, usedOfflineFallback: Boolean) {
                        isQueryInFlight = false
                        onFritkotsLoaded(location, fritkots, usedOfflineFallback)
                    }
                }
            )
        } else if (nearby.isNotEmpty()) {
            // Cheap update: recompute distance/bearing against the last known
            // fritkot list using the fresher location, without re-querying.
            recomputeAndRender(location)
        }
    }

    private fun onFritkotsLoaded(location: Location, fritkots: List<Fritkot>, usedOfflineFallback: Boolean) {
        nearby = fritkots
            .map {
                FritkotWithBearing(
                    fritkot = it,
                    distanceMeters = GeoUtils.distanceMeters(location.latitude, location.longitude, it.lat, it.lon),
                    bearingDegrees = GeoUtils.bearingDegrees(location.latitude, location.longitude, it.lat, it.lon)
                )
            }
            .sortedBy { it.distanceMeters }

        tvStatus.text = if (usedOfflineFallback) getString(R.string.status_offline) else ""
        renderAll()
    }

    private fun recomputeAndRender(location: Location) {
        nearby = nearby
            .map {
                it.copy(
                    distanceMeters = GeoUtils.distanceMeters(location.latitude, location.longitude, it.fritkot.lat, it.fritkot.lon),
                    bearingDegrees = GeoUtils.bearingDegrees(location.latitude, location.longitude, it.fritkot.lat, it.fritkot.lon)
                )
            }
            .sortedBy { it.distanceMeters }
        renderAll()
    }

    // ---- Selection & rendering ------------------------------------------

    /**
     * Resolves which fritkot to display/point at: the explicitly selected
     * one if it's still in [nearby], otherwise the closest one — which is
     * also the default when nothing has been selected yet. If a selection
     * no longer resolves (e.g. a fresh query no longer includes it), it's
     * cleared so the app falls back to following the closest again.
     */
    private fun selectedTarget(): FritkotWithBearing? {
        val id = selectedFritkotId
        if (id != null) {
            val match = nearby.find { it.fritkot.id == id }
            if (match != null) return match
            selectedFritkotId = null
        }
        return nearby.firstOrNull()
    }

    private fun renderAll() {
        val target = selectedTarget()
        renderTarget(target)
        renderNearbyList(target)
    }

    private fun renderTarget(target: FritkotWithBearing?) {
        if (target == null) return
        tvDistance.text = GeoUtils.formatDistance(target.distanceMeters)
        tvName.text = target.fritkot.name ?: getString(R.string.unknown_name)
        tvAddress.text = target.fritkot.address
        compassView.targetBearingDegrees = target.bearingDegrees.toFloat()
    }

    /** Shows the closest [maxSelectable] fritkots; tap one to track it instead of the closest. */
    private fun renderNearbyList(target: FritkotWithBearing?) {
        llNearby.removeAllViews()
        val selectableBackground = TypedValue().also {
            theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }

        for (item in nearby.take(maxSelectable)) {
            val isSelected = target != null && item.fritkot.id == target.fritkot.id
            val label = item.fritkot.name ?: getString(R.string.unknown_name)

            val row = TextView(this)
            row.text = (if (isSelected) "▸ " else "   ") + "$label — ${GeoUtils.formatDistance(item.distanceMeters)}"
            row.setTextColor(if (isSelected) 0xFFF2B705.toInt() else 0xFFCBB891.toInt())
            row.typeface = Typeface.defaultFromStyle(if (isSelected) Typeface.BOLD else Typeface.NORMAL)
            row.textSize = 15f
            row.setPadding(12, 22, 12, 22)
            row.isClickable = true
            row.isFocusable = true
            if (selectableBackground.resourceId != 0) {
                row.setBackgroundResource(selectableBackground.resourceId)
            }
            row.setOnClickListener {
                selectedFritkotId = item.fritkot.id
                renderAll()
            }
            llNearby.addView(row)
        }
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // ---- Compass sensor -----------------------------------------------

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientation)

        var azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
        if (azimuth < 0) azimuth += 360f

        smoothedAzimuth = lowPassAngle(smoothedAzimuth, azimuth, if (hasRotationFix) 0.15f else 1f)
        hasRotationFix = true

        compassView.hasHeading = true
        compassView.deviceHeadingDegrees = smoothedAzimuth
    }

    /** Exponential smoothing for a wraparound angle (0-360), taking the shortest path. */
    private fun lowPassAngle(current: Float, target: Float, alpha: Float): Float {
        var diff = target - current
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f
        var result = current + alpha * diff
        if (result < 0f) result += 360f
        if (result >= 360f) result -= 360f
        return result
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
