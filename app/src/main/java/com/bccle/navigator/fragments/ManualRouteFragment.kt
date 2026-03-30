package com.bccle.navigator.fragments

import android.os.Bundle
import com.bccle.navigator.BuildConfig
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.bccle.navigator.R
import com.bccle.navigator.db.AppDatabase
import com.bccle.navigator.db.LatLngDto
import com.bccle.navigator.db.RideEntity
import com.bccle.navigator.fragments.helpers.LocationKit
import com.bccle.navigator.fragments.helpers.LocationPermissionRequester
import com.bccle.navigator.fragments.helpers.Units
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import com.bccle.navigator.fragments.helpers.OrsClient
import com.bccle.navigator.fragments.helpers.OrsDirectionsBody
import com.bccle.navigator.fragments.helpers.UnitSystem
import com.bccle.navigator.fragments.helpers.UserPrefs
import com.bccle.navigator.fragments.helpers.installHideKeyboardOnTouchOutside

class ManualRouteFragment : Fragment(R.layout.fragment_manual_route) {

    // --- Views ---
    private lateinit var mapView: MapView
    private lateinit var addBtn: TextView
    private lateinit var clearBtn: TextView
    private lateinit var removeBtn: TextView
    private lateinit var routeNameEt: EditText
    private lateinit var distanceTv: TextView
    private lateinit var timeTv: TextView
    private lateinit var difficultyTv: TextView
    private lateinit var generateBtn: TextView

    // Map + overlays
    private val waypoints = mutableListOf<GeoPoint>()
    private val markers = mutableListOf<Marker>()
    private var selectedMarker: Marker? = null
    private var routePolyline: Polyline? = null
    private var isAddMode = false

    // Routing
    private val ors by lazy { OrsClient.create(logging = false) }
    private var routeJob: Job? = null
    private var routedPoints: List<GeoPoint> = emptyList()

    // Location
    private lateinit var locationRequester: LocationPermissionRequester
    private var myLocationOverlay: MyLocationNewOverlay? = null

    // Units
    private var currentUnit: UnitSystem = UnitSystem.METRIC
    private var unitJob: Job? = null

    companion object {
        const val ARG_RIDE_ID = "arg_ride_id"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mapView      = view.findViewById(R.id.map)
        addBtn       = view.findViewById(R.id.addPoint)
        clearBtn     = view.findViewById(R.id.clearAll)
        removeBtn    = view.findViewById(R.id.removePoint)
        routeNameEt  = view.findViewById(R.id.routeName)
        distanceTv   = view.findViewById(R.id.distance)
        timeTv       = view.findViewById(R.id.time)
        difficultyTv = view.findViewById(R.id.difficulty)
        generateBtn  = view.findViewById(R.id.generateRouteBtn)

        view.isClickable = true
        view.isFocusableInTouchMode = true
        view.installHideKeyboardOnTouchOutside()

        // observe unit system
        unitJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                UserPrefs.unitFlow(requireContext()).collect { unit ->
                    currentUnit = unit
                    // re-render stats with the same routed points
                    updateStats(routedPoints)
                }
            }
        }

        // osmdroid
        val base = requireContext().cacheDir.resolve("osmdroid")
        Configuration.getInstance().apply {
            userAgentValue = requireContext().packageName
            osmdroidBasePath = base
            osmdroidTileCache = base.resolve("tiles")
        }
        mapView.setMultiTouchControls(true)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.controller.setZoom(13.0)

        // Tap to add waypoint when in add mode
        mapView.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                if (!isAddMode || p == null) return false
                addWaypoint(p)
                toggleAddMode(false)
                return true
            }
            override fun longPressHelper(p: GeoPoint?) = false
        }))

        // Buttons
        addBtn.setOnClickListener { toggleAddMode(!isAddMode) }
        clearBtn.setOnClickListener { clearAll() }
        removeBtn.setOnClickListener { removeSelected() }
        removeBtn.isEnabled = false

        generateBtn.setOnClickListener {
            generateBtn.isEnabled = false
            generateAndNavigate()
        }

        // Center on user
        locationRequester = LocationPermissionRequester(
            fragment = this,
            onGranted = {
                LocationKit.toGeoPoint(
                    LocationKit.getBestLastKnownLocation(requireContext())
                )?.let { p ->
                    mapView.controller.setZoom(15.0)
                    mapView.controller.setCenter(p)
                }
                myLocationOverlay = LocationKit.attachMyLocationOverlay(
                    mapView = mapView, context = requireContext(), follow = false
                ) { firstFix ->
                    requireActivity().runOnUiThread { mapView.controller.animateTo(firstFix) }
                }
                mapView.overlays.add(myLocationOverlay)
            },
            onDenied = { permanently ->
                if (permanently) LocationKit.openAppSettings(this)
                else Toast.makeText(requireContext(), "Location permission is needed to center the map.", Toast.LENGTH_LONG).show()
            }
        )
        locationRequester.request()

        updateStats(emptyList())
    }

    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause()  { mapView.onPause(); super.onPause() }
    override fun onDestroyView() {
        myLocationOverlay?.disableMyLocation()
        myLocationOverlay = null
        routeJob?.cancel()
        unitJob?.cancel()
        super.onDestroyView()
    }

    // ---------- Waypoints ----------
    private fun toggleAddMode(enabled: Boolean) {
        isAddMode = enabled
        val bg = if (enabled) R.drawable.basic_button_small_corners else R.drawable.button_outline_black
        val col = if (enabled) R.color.white else R.color.black
        addBtn.setBackgroundResource(bg)
        addBtn.setTextColor(ContextCompat.getColor(requireContext(), col))
    }

    private fun addWaypoint(p: GeoPoint) {
        waypoints.add(p)
        addMarkerForPoint(p, waypoints.size)
        reroute()
    }

    private fun addMarkerForPoint(p: GeoPoint, number: Int) {
        val m = Marker(mapView).apply {
            position = p
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Point $number"
            setOnMarkerClickListener { marker, _ ->
                selectMarker(marker); true
            }
        }
        markers.add(m)
        mapView.overlays.add(m)
        mapView.invalidate()
    }

    private fun selectMarker(m: Marker) {
        selectedMarker?.icon = null // restore default
        selectedMarker = m
        m.icon = ContextCompat.getDrawable(requireContext(), R.drawable.button_round)
        removeBtn.isEnabled = true
        mapView.invalidate()
    }

    private fun renumberMarkers() {
        markers.forEachIndexed { idx, marker -> marker.title = "Point ${idx + 1}" }
    }

    private fun removeSelected() {
        val m = selectedMarker ?: return
        val idx = markers.indexOf(m)
        if (idx >= 0) {
            mapView.overlays.remove(m)
            markers.removeAt(idx)
            waypoints.removeAt(idx)
            selectedMarker = null
            removeBtn.isEnabled = false
            renumberMarkers()
            reroute()
        }
    }

    private fun clearAll() {
        routeJob?.cancel()
        markers.forEach { mapView.overlays.remove(it) }
        markers.clear()
        waypoints.clear()
        selectedMarker = null
        removeBtn.isEnabled = false
        routePolyline?.let { mapView.overlays.remove(it) }
        routePolyline = null
        routedPoints = emptyList()
        mapView.invalidate()
        updateStats(emptyList())
    }

    // ---------- Routing ----------
    private fun reroute() {
        routeJob?.cancel()
        if (waypoints.size < 2) {
            routePolyline?.let { mapView.overlays.remove(it) }
            routePolyline = null
            waypoints.firstOrNull()?.let { mapView.controller.setCenter(it) }
            mapView.invalidate()
            updateStats(emptyList())
            return
        }

        routeJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val coords = waypoints.map { listOf(it.longitude, it.latitude) }
                val body = OrsDirectionsBody(
                    coordinates = coords,
                    instructions = false,
                    elevation = false,
                    geometrySimplify = true
                )
                val apiKey = BuildConfig.ORS_API_KEY
                val resp = withContext(Dispatchers.IO) {
                    ors.routeGeoJson(apiKey, "cycling-regular", body)
                }
                val points = resp.features.firstOrNull()?.geometry?.coordinates.orEmpty()
                    .map { ll -> GeoPoint(ll[1], ll[0]) }

                routedPoints = points
                drawRoute(points)
                updateStats(points)
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Route error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun drawRoute(points: List<GeoPoint>) {
        routePolyline?.let { mapView.overlays.remove(it) }
        routePolyline = null
        if (points.isNotEmpty()) {
            routePolyline = Polyline().apply {
                setPoints(points)
                outlinePaint.strokeWidth = 6f
                outlinePaint.color = 0xFFE53935.toInt()
            }
            mapView.overlays.add(routePolyline)
            val bbox = BoundingBox.fromGeoPointsSafe(points)
            mapView.zoomToBoundingBox(bbox, true, 80)
        }
        mapView.invalidate()
    }

    // ---------- Stats (unit-aware) ----------
    private fun updateStats(points: List<GeoPoint>) {
        val meters = polylineLength(points)
        val km = meters / 1000.0
        val hours = if (meters > 0) km / 15.0 else 0.0
        val h = floor(hours).toInt()
        val m = ((hours - h) * 60.0).roundToInt()

        // distance in user units
        distanceTv.text = Units.formatDistance(meters, currentUnit)
        timeTv.text = if (h > 0) "${h} h ${m} min" else "$m min"
        difficultyTv.text = "${difficultyForDistance(meters)}/5"
    }

    private fun polylineLength(points: List<GeoPoint>): Int {
        if (points.size < 2) return 0
        var sum = 0.0
        for (i in 1 until points.size) sum += haversine(points[i - 1], points[i])
        return sum.roundToInt()
    }

    private fun haversine(a: GeoPoint, b: GeoPoint): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val sinDLat = sin(dLat / 2)
        val sinDLon = sin(dLon / 2)
        val c = 2 * asin(sqrt(sinDLat * sinDLat + cos(lat1) * cos(lat2) * sinDLon * sinDLon))
        return R * c
    }

    private fun difficultyForDistance(meters: Int): Int {
        val km = meters / 1000.0
        return when {
            km <= 0      -> 1
            km < 10      -> 1
            km < 30      -> 2
            km < 60      -> 3
            km < 100     -> 4
            else         -> 5
        }
    }

    // ---------- Save & Navigate ----------
    private fun generateAndNavigate() {
        if (waypoints.size < 2 || routedPoints.isEmpty()) {
            Toast.makeText(requireContext(), "Add at least two points", Toast.LENGTH_SHORT).show()
            generateBtn.isEnabled = true
            return
        }

        val name = routeNameEt.text?.toString()?.trim().orEmpty()
        val meters = polylineLength(routedPoints)
        val km = meters / 1000.0
        val durationSeconds = ((km / 15.0) * 3600.0).roundToLong().coerceAtLeast(300L)

        val line = routedPoints.map { LatLngDto(it.latitude, it.longitude) }
        val polyJson = Gson().toJson(line)
        val start = waypoints.first()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.getInstance(requireContext()).rideDao()
            val ride = RideEntity(
                name = name,
                startLat = start.latitude,
                startLon = start.longitude,
                specLengthMeters = meters,
                specProfile = "cycling-regular",
                specSeed = 0,
                specDir = "CLOCKWISE",
                polylineJson = polyJson,
                distanceMeters = meters,
                durationSeconds = durationSeconds,
                avgSpeedKmh = null,
                difficulty = difficultyForDistance(meters),
                description = null,
                rating = null,
                createdAt = System.currentTimeMillis()
            )
            val rideId = dao.insert(ride)
            if (name.isBlank()) dao.updateName(rideId, "Route $rideId")

            withContext(Dispatchers.Main) {
                generateBtn.isEnabled = true
                findNavController().navigate(
                    R.id.navigatorFragment,
                    bundleOf(ARG_RIDE_ID to rideId)
                )
            }
        }
    }
}