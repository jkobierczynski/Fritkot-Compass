// SPDX-License-Identifier: GPL-3.0-or-later
package be.fritkot.compass

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File

/**
 * A draggable, pinch-zoomable OpenStreetMap view (via osmdroid). Tap
 * anywhere to search for the nearest fritkots to that point instead of
 * your current GPS location — handy for planning a trip to another town,
 * or just browsing.
 */
class MapActivity : Activity() {

    private lateinit var mapView: MapView
    private lateinit var tvMapStatus: TextView
    private lateinit var btnMapDataSource: Button
    private lateinit var llMapNearby: LinearLayout
    private lateinit var mapBottomPanel: View
    private lateinit var overpassClient: OverpassClient

    private var forceOffline = false
    private var isQueryInFlight = false
    private var pickedPoint: GeoPoint? = null
    private var pickedMarker: Marker? = null
    private val fritkotMarkers = mutableListOf<Marker>()

    private val searchRadiusMeters = 30_000
    private val maxShown = 5

    // Default view when we have no location fix yet: centred on Belgium as
    // a whole (Brussels) rather than anywhere more specific.
    private val defaultCenter = GeoPoint(50.8503, 4.3517)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid must be configured before any MapView is created/inflated
        // (setContentView below does exactly that). A user agent is required
        // by OSM's tile usage policy; pointing the cache at this app's own
        // cache directory avoids needing any storage permission.
        val prefs = getSharedPreferences("osmdroid_prefs", MODE_PRIVATE)
        Configuration.getInstance().load(applicationContext, prefs)
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().osmdroidBasePath = File(cacheDir, "osmdroid")
        Configuration.getInstance().osmdroidTileCache = File(cacheDir, "osmdroid/tiles")

        setContentView(R.layout.activity_map)

        mapView = findViewById(R.id.mapView)
        tvMapStatus = findViewById(R.id.tvMapStatus)
        btnMapDataSource = findViewById(R.id.btnMapDataSource)
        llMapNearby = findViewById(R.id.llMapNearby)
        mapBottomPanel = findViewById(R.id.mapBottomPanel)
        overpassClient = OverpassClient(applicationContext)

        mapView.setTileSource(TileSourceFactory.MAPNIK)
        // Pinch-to-zoom and drag-to-pan both come for free from osmdroid
        // once multitouch is enabled; setBuiltInZoomControls just adds
        // optional on-screen +/- buttons as well.
        mapView.setMultiTouchControls(true)
        mapView.setBuiltInZoomControls(true)
        mapView.overlays.add(CopyrightOverlay(this)) // required OSM data attribution

        val here = getBestLastKnownLocation()
        mapView.controller.setZoom(if (here != null) 14.0 else 8.0)
        mapView.controller.setCenter(here?.let { GeoPoint(it.latitude, it.longitude) } ?: defaultCenter)

        val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                onMapTapped(p)
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean = false
        })
        mapView.overlays.add(eventsOverlay)

        btnMapDataSource.text = getString(R.string.use_offline)
        btnMapDataSource.setOnClickListener {
            forceOffline = !forceOffline
            btnMapDataSource.text = getString(if (forceOffline) R.string.use_online else R.string.use_offline)
            pickedPoint?.let { fetchForPoint(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    private fun getBestLastKnownLocation(): Location? {
        val hasPermission = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return null

        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (provider in locationManager.getProviders(true)) {
            @Suppress("MissingPermission")
            val candidate = locationManager.getLastKnownLocation(provider) ?: continue
            if (best == null || candidate.time > best!!.time) best = candidate
        }
        return best
    }

    private fun onMapTapped(point: GeoPoint) {
        pickedPoint = point

        val marker = pickedMarker ?: Marker(mapView).also {
            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            mapView.overlays.add(it)
            pickedMarker = it
        }
        marker.setPosition(point)
        marker.setTitle(getString(R.string.map_title))
        mapView.invalidate()

        fetchForPoint(point)
    }

    private fun fetchForPoint(point: GeoPoint) {
        if (isQueryInFlight) return
        isQueryInFlight = true
        tvMapStatus.text = if (forceOffline) getString(R.string.status_offline_selected) else getString(R.string.status_searching)

        val callback = object : OverpassClient.Callback {
            override fun onResult(fritkots: List<Fritkot>, usedOfflineFallback: Boolean) {
                isQueryInFlight = false
                onFritkotsLoaded(point, fritkots, usedOfflineFallback)
            }
        }

        if (forceOffline) {
            overpassClient.fetchOfflineOnly(callback)
        } else {
            overpassClient.fetchNearby(point.latitude, point.longitude, searchRadiusMeters, callback)
        }
    }

    private fun onFritkotsLoaded(point: GeoPoint, fritkots: List<Fritkot>, usedOfflineFallback: Boolean) {
        val ranked = fritkots
            .map {
                FritkotWithBearing(
                    fritkot = it,
                    distanceMeters = GeoUtils.distanceMeters(point.latitude, point.longitude, it.lat, it.lon),
                    bearingDegrees = GeoUtils.bearingDegrees(point.latitude, point.longitude, it.lat, it.lon)
                )
            }
            .sortedBy { it.distanceMeters }
            .take(maxShown)

        tvMapStatus.text = when {
            forceOffline -> getString(R.string.status_offline_selected)
            usedOfflineFallback -> getString(R.string.status_offline)
            else -> getString(R.string.map_nearby_label)
        }

        renderFritkotMarkers(ranked)
        renderFritkotList(ranked)
    }

    private fun renderFritkotMarkers(items: List<FritkotWithBearing>) {
        for (marker in fritkotMarkers) mapView.overlays.remove(marker)
        fritkotMarkers.clear()

        for (item in items) {
            val marker = Marker(mapView)
            marker.setPosition(GeoPoint(item.fritkot.lat, item.fritkot.lon))
            marker.setTitle(item.fritkot.name ?: getString(R.string.unknown_name))
            marker.setSnippet(GeoUtils.formatDistance(item.distanceMeters))
            mapView.overlays.add(marker)
            fritkotMarkers.add(marker)
        }
        mapView.invalidate()
    }

    /** Tap a row to re-centre the map on that fritkot (its marker's info bubble shows on tap too). */
    private fun renderFritkotList(items: List<FritkotWithBearing>) {
        llMapNearby.removeAllViews()
        for (item in items) {
            val row = TextView(this)
            val label = item.fritkot.name ?: getString(R.string.unknown_name)
            row.text = "$label — ${GeoUtils.formatDistance(item.distanceMeters)}"
            row.setTextColor(0xFFCBB891.toInt())
            row.textSize = 14f
            row.setPadding(0, 12, 0, 12)
            row.isClickable = true
            row.setOnClickListener {
                centerAboveBottomPanel(GeoPoint(item.fritkot.lat, item.fritkot.lon))
            }
            llMapNearby.addView(row)
        }
    }

    /**
     * Centres the map on [target], but accounting for the bottom panel that
     * covers the lower part of the screen. `MapView.controller.animateTo()`
     * centres on the *full* view — since the panel with the status text,
     * data-source button and fritkot list sits on top of the bottom of that
     * view, a plain animateTo() puts the picked fritkot behind (or right at
     * the edge of) the panel instead of visibly centred on the part of the
     * map you can actually see. This nudges the real animation target south
     * by half the panel's height (in map pixels, converted to latitude at
     * the current zoom) so [target] itself ends up centred in the visible
     * area above the panel.
     */
    private fun centerAboveBottomPanel(target: GeoPoint) {
        val panelHeight = mapBottomPanel.height
        val w = mapView.width
        val h = mapView.height
        if (panelHeight <= 0 || w <= 0 || h <= 0) {
            // Not laid out yet (shouldn't normally happen once the list is
            // visible and clickable) — fall back to plain centring.
            mapView.controller.animateTo(target)
            return
        }

        val proj = mapView.projection
        val centerGeo = proj.fromPixels(w / 2, h / 2)
        val shiftedGeo = proj.fromPixels(w / 2, h / 2 + panelHeight / 2)
        val latDelta = centerGeo.latitude - shiftedGeo.latitude

        mapView.controller.animateTo(GeoPoint(target.latitude - latDelta, target.longitude))
    }
}
