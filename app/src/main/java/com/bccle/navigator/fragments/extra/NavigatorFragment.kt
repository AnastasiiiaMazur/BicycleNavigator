package com.bccle.navigator.fragments.extra

import android.os.Build
import android.os.Bundle
import com.bccle.navigator.BuildConfig
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.bccle.navigator.R
import com.bccle.navigator.db.AppDatabase
import com.bccle.navigator.db.RideEntity
import com.bccle.navigator.db.RouteSpec
import com.bccle.navigator.fragments.helpers.LocationKit
import com.bccle.navigator.fragments.helpers.LocationPermissionRequester
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import com.bccle.navigator.fragments.helpers.OrsClient
import com.bccle.navigator.fragments.helpers.UnitSystem
import com.bccle.navigator.fragments.helpers.UserPrefs
import com.bccle.navigator.db.LatLngDto
import com.bccle.navigator.fragments.helpers.OrsDirectionsBody
import com.bccle.navigator.fragments.helpers.OrsOptions
import com.bccle.navigator.fragments.helpers.OrsRoundTrip
import com.bccle.navigator.fragments.helpers.Units

class NavigatorFragment : Fragment(R.layout.fragment_navigator) {

    private val ors by lazy { OrsClient.create(logging = false) }

    private lateinit var mapView: MapView
    private lateinit var distanceTv: TextView
    private lateinit var timeTv: TextView
    private lateinit var difficultyTv: TextView
    private lateinit var startNavBtn: TextView
    private lateinit var saveRouteBtn: TextView

    private var routePolyline: Polyline? = null
    private var fetchJob: Job? = null

    // location / navigation state
    private lateinit var locationRequester: LocationPermissionRequester
    private var myLocationOverlay: MyLocationNewOverlay? = null

    private enum class NavState { IDLE, RUNNING, PAUSED }
    private var navState: NavState = NavState.IDLE
    private var startedAtMs: Long = 0L
    private var accumulatedSec: Long = 0L
    private var tickerJob: Job? = null

    // args + data
    private var rideIdArg: Long? = null
    private var specArg: RouteSpec? = null
    private var loadedRide: RideEntity? = null

    // unit system
    private var currentUnit: UnitSystem = UnitSystem.METRIC
    private var unitCollectorJob: Job? = null

    companion object {
        const val ARG_RIDE_ID = "arg_ride_id"
        const val ARG_ROUTE_SPEC = "arg_route_spec"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mapView = view.findViewById(R.id.map)
        distanceTv = view.findViewById(R.id.distance)
        timeTv = view.findViewById(R.id.time)
        difficultyTv = view.findViewById(R.id.difficulty)
        startNavBtn = view.findViewById(R.id.startNavBtn)
        saveRouteBtn = view.findViewById(R.id.saveRouteBtn)

        // osmdroid basics
        val base = requireContext().cacheDir.resolve("osmdroid")
        Configuration.getInstance().apply {
            userAgentValue = requireContext().packageName
            osmdroidBasePath = base
            osmdroidTileCache = base.resolve("tiles")
        }
        mapView.setMultiTouchControls(true)
        mapView.setTileSource(TileSourceFactory.MAPNIK)

        // read args
        rideIdArg = arguments?.getLong(ARG_RIDE_ID, 0L)?.takeIf { it > 0 }
        specArg = if (rideIdArg == null) {
            if (Build.VERSION.SDK_INT >= 33)
                arguments?.getParcelable(ARG_ROUTE_SPEC, RouteSpec::class.java)
            else
                @Suppress("DEPRECATION") arguments?.getParcelable(ARG_ROUTE_SPEC)
        } else null

        if (rideIdArg == null && specArg == null) {
            Toast.makeText(requireContext(), "No route data.", Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
            return
        }

        // observe unit system
        unitCollectorJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                UserPrefs.unitFlow(requireContext()).collect { unit ->
                    currentUnit = unit
                    refreshHeaderDistance() // redraw header distance on unit change
                }
            }
        }

        // location permission helper
        locationRequester = LocationPermissionRequester(
            fragment = this,
            onGranted = { startFollowingIfNeeded() },
            onDenied = { permanently ->
                if (permanently) LocationKit.openAppSettings(this)
                else Toast.makeText(requireContext(), "Location permission is required.", Toast.LENGTH_LONG).show()
            }
        )

        // buttons
        startNavBtn.setOnClickListener { toggleStartStop() }
        saveRouteBtn.setOnClickListener { onSavePressed() }

        // draw route
        if (rideIdArg != null) {
            loadRideAndDraw(rideIdArg!!)
        } else {
            val spec = specArg!!
            bindHeaderFromSpec(spec)           // sets distance/time/difficulty with units
            fetchJob?.cancel()
            fetchJob = viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val pts = fetchRouteForSpec(spec)
                    drawPolyline(pts.map { GeoPoint(it.lat, it.lon) })
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Routing failed", Toast.LENGTH_LONG).show()
                }
            }
        }

        updateStartButtonLabel()
    }

    override fun onDestroyView() {
        fetchJob?.cancel()
        tickerJob?.cancel()
        unitCollectorJob?.cancel()
        myLocationOverlay?.disableMyLocation()
        myLocationOverlay = null
        routePolyline = null
        super.onDestroyView()
    }

    // ------------------- Load & header -------------------

    private fun loadRideAndDraw(id: Long) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.getInstance(requireContext()).rideDao()
            val ride = dao.getById(id)
            withContext(Dispatchers.Main) {
                if (ride == null) {
                    Toast.makeText(requireContext(), "Ride not found", Toast.LENGTH_SHORT).show()
                    findNavController().popBackStack()
                } else {
                    loadedRide = ride
                    bindHeaderFromRide(ride) // unit-aware
                    // draw saved polyline or regenerate
                    val pts: List<GeoPoint> = ride.polylineJson?.let { json ->
                        try {
                            val list = Gson().fromJson(json, Array<LatLngDto>::class.java).toList()
                            list.map { GeoPoint(it.lat, it.lon) }
                        } catch (_: Exception) { emptyList() }
                    }.orEmpty()

                    if (pts.isNotEmpty()) {
                        drawPolyline(pts)
                    } else {
                        val spec = RouteSpec(
                            start = LatLngDto(ride.startLat, ride.startLon),
                            lengthMeters = ride.specLengthMeters,
                            profile = ride.specProfile,
                            seed = ride.specSeed,
                            dir = ride.specDir
                        )
                        fetchJob?.cancel()
                        fetchJob = viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                val list = fetchRouteForSpec(spec)
                                drawPolyline(list.map { GeoPoint(it.lat, it.lon) })
                            } catch (e: Exception) {
                                Toast.makeText(requireContext(), "Routing failed", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun bindHeaderFromRide(ride: RideEntity) {
        distanceTv.text = Units.formatDistance(ride.distanceMeters, currentUnit)
        timeTv.text = formatDuration(ride.durationSeconds)
        val diff = (ride.difficulty ?: difficultyForDistance(ride.distanceMeters)).coerceIn(1, 5)
        difficultyTv.text = "$diff/5"
    }

    private fun bindHeaderFromSpec(spec: RouteSpec) {
        distanceTv.text = Units.formatDistance(spec.lengthMeters, currentUnit)
        timeTv.text = "~" + estimateTimeText(spec.lengthMeters)
        difficultyTv.text = "${difficultyForDistance(spec.lengthMeters)}/5"
    }

    private fun refreshHeaderDistance() {
        loadedRide?.let {
            distanceTv.text = Units.formatDistance(it.distanceMeters, currentUnit)
            return
        }
        specArg?.let {
            distanceTv.text = Units.formatDistance(it.lengthMeters, currentUnit)
            return
        }
    }

    // ------------------- Start/Stop/Resume -------------------

    private fun toggleStartStop() {
        when (navState) {
            NavState.IDLE, NavState.PAUSED -> {
                locationRequester.request()
                startTimer()
                navState = NavState.RUNNING
            }
            NavState.RUNNING -> {
                stopTimer()
                navState = NavState.PAUSED
            }
        }
        updateStartButtonLabel()
    }

    private fun startFollowingIfNeeded() {
        if (myLocationOverlay == null) {
            myLocationOverlay = LocationKit.attachMyLocationOverlay(
                mapView = mapView, context = requireContext(), follow = true
            ) { firstFix -> requireActivity().runOnUiThread { mapView.controller.animateTo(firstFix) } }
            mapView.overlays.add(myLocationOverlay)
        } else {
            myLocationOverlay?.enableFollowLocation()
        }
    }

    private fun startTimer() {
        startedAtMs = SystemClock.elapsedRealtime()
        tickerJob?.cancel()
        tickerJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val runningSec = accumulatedSec + ((SystemClock.elapsedRealtime() - startedAtMs) / 1000L)
                timeTv.text = formatDuration(runningSec)
                delay(1000)
            }
        }
    }

    private fun stopTimer() {
        val delta = (SystemClock.elapsedRealtime() - startedAtMs) / 1000L
        accumulatedSec += delta
        tickerJob?.cancel()
    }

    private fun currentDurationSeconds(): Long =
        if (navState == NavState.RUNNING) {
            accumulatedSec + ((SystemClock.elapsedRealtime() - startedAtMs) / 1000L)
        } else accumulatedSec

    private fun updateStartButtonLabel() {
        startNavBtn.text = when (navState) {
            NavState.IDLE   -> getString(R.string.start_navigation)
            NavState.RUNNING-> getString(R.string.stop_navigation)
            NavState.PAUSED -> getString(R.string.resume_navigation)
        }
    }

    // ------------------- SAVE -------------------

    private fun onSavePressed() {
        val start = loadedRide?.let { GeoPoint(it.startLat, it.startLon) }
            ?: specArg?.let { GeoPoint(it.start.lat, it.start.lon) }

        val cur = myLocationOverlay?.myLocation
            ?: LocationKit.toGeoPoint(LocationKit.getBestLastKnownLocation(requireContext()))

        if (start == null || cur == null) {
            Toast.makeText(requireContext(), "No location yet.", Toast.LENGTH_SHORT).show(); return
        }

        val distanceMeters = start.distanceToAsDouble(cur).roundToInt()
        val durationSeconds = currentDurationSeconds().coerceAtLeast(0L)
        val avgKmh = if (durationSeconds > 0)
            (distanceMeters / 1000.0) / (durationSeconds / 3600.0) else null
        val diff = difficultyForDistance(distanceMeters)

        if (rideIdArg != null) {
            // Update existing (manual route path)
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val dao = AppDatabase.getInstance(requireContext()).rideDao()
                dao.updateTrackingStats(
                    id = rideIdArg!!,
                    distance = distanceMeters,
                    duration = durationSeconds,
                    avgKmh = avgKmh,
                    difficulty = diff
                )
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Ride updated", Toast.LENGTH_SHORT).show()
                    findNavController().navigate(R.id.savedRoutesFragment)
                }
            }
        } else {
            // Insert new (auto-route path)
            val spec = specArg!!
            val entity = RideEntity(
                name = "",
                startLat = spec.start.lat,
                startLon = spec.start.lon,
                specLengthMeters = spec.lengthMeters,
                specProfile = spec.profile,
                specSeed = spec.seed,
                specDir = spec.dir,
                polylineJson = null,
                distanceMeters = distanceMeters,
                durationSeconds = durationSeconds,
                avgSpeedKmh = avgKmh,
                difficulty = diff,
                description = null,
                rating = null,
                createdAt = System.currentTimeMillis()
            )
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val dao = AppDatabase.getInstance(requireContext()).rideDao()
                val id = dao.insert(entity)
                dao.updateName(id, "Route $id")
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Ride saved", Toast.LENGTH_SHORT).show()
                    findNavController().navigate(R.id.savedRoutesFragment)
                }
            }
        }
    }

    // ------------------- ORS & draw -------------------

    private suspend fun fetchRouteForSpec(spec: RouteSpec): List<LatLngDto> = withContext(Dispatchers.IO) {
        val body = OrsDirectionsBody(
            coordinates = listOf(listOf(spec.start.lon, spec.start.lat)),
            options = OrsOptions(
                roundTrip = OrsRoundTrip(
                    length = spec.lengthMeters.coerceIn(1_000, 150_000),
                    points = 5,
                    seed = spec.seed
                )
            ),
            instructions = false,
            elevation = false,
            geometrySimplify = true
        )
        val apiKey = BuildConfig.ORS_API_KEY
        val resp = ors.routeGeoJson((apiKey), spec.profile, body)
        var geo = resp.features.firstOrNull()?.geometry?.coordinates.orEmpty()
            .map { (lon, lat) -> GeoPoint(lat, lon) }
        if (spec.dir == "COUNTERCLOCKWISE") geo = geo.asReversed()
        val simplified = withContext(Dispatchers.Default) { simplifyForMap(geo) }
        simplified.map { LatLngDto(it.latitude, it.longitude) }
    }

    private fun drawPolyline(points: List<GeoPoint>) {
        routePolyline?.let { mapView.overlays.remove(it) }
        routePolyline = null
        if (points.isEmpty()) { mapView.invalidate(); return }
        routePolyline = Polyline().apply {
            setPoints(points)
            outlinePaint.strokeWidth = 6f
            outlinePaint.color = 0xFFE53935.toInt()
        }
        mapView.overlays.add(routePolyline)
        val bbox: BoundingBox = BoundingBox.fromGeoPointsSafe(points)
        mapView.zoomToBoundingBox(bbox, true, 80)
        mapView.invalidate()
    }

    // ------------------- helpers -------------------

    private fun estimateTimeText(distanceMeters: Int): String {
        val hours = distanceMeters / 1000.0 / 15.0
        val h = floor(hours).toInt()
        val m = ((hours - h) * 60).roundToInt()
        return if (h > 0) "${h} h ${m} min" else "$m min"
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

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format("%dh %02dm %02ds", h, m, s)
        else String.format("%dm %02ds", m, s)
    }

    private fun simplifyForMap(points: List<GeoPoint>, toleranceMeters: Double = 10.0, maxPoints: Int = 400): List<GeoPoint> {
        if (points.size <= 2) return points
        val dp = douglasPeucker(points, toleranceMeters)
        return capPoints(dp, maxPoints)
    }
    private fun capPoints(points: List<GeoPoint>, maxPoints: Int): List<GeoPoint> {
        if (points.size <= maxPoints) return points
        val step = ceil(points.size / maxPoints.toDouble()).toInt()
        val out = ArrayList<GeoPoint>((points.size / step) + 1)
        for (i in points.indices step step) out.add(points[i])
        if (out.last() != points.last()) out.add(points.last())
        return out
    }
    private fun douglasPeucker(points: List<GeoPoint>, toleranceMeters: Double): List<GeoPoint> {
        val n = points.size
        if (n < 3) return points
        val keep = BooleanArray(n).apply { this[0] = true; this[n - 1] = true }
        val refLat = points.first().latitude
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(refLat))
        fun toXY(p: GeoPoint) = Pair(p.longitude * mPerDegLon, p.latitude * mPerDegLat)
        val xy = points.map { toXY(it) }
        fun perpDist(i: Int, a: Int, b: Int): Double {
            val (x, y) = xy[i]; val (x1, y1) = xy[a]; val (x2, y2) = xy[b]
            if (x1 == x2 && y1 == y2) return hypot(x - x1, y - y1)
            val dx = x2 - x1; val dy = y2 - y1
            val t = ((x - x1) * dx + (y - y1) * dy) / (dx*dx + dy*dy)
            val tc = t.coerceIn(0.0, 1.0)
            val px = x1 + tc * dx; val py = y1 + tc * dy
            return hypot(x - px, y - py)
        }
        fun rec(a: Int, b: Int) {
            var maxD = 0.0; var idx = -1
            for (i in a + 1 until b) {
                val d = perpDist(i, a, b)
                if (d > maxD) { maxD = d; idx = i }
            }
            if (maxD > toleranceMeters && idx != -1) { keep[idx] = true; rec(a, idx); rec(idx, b) }
        }
        rec(0, n - 1)
        val out = ArrayList<GeoPoint>()
        for (i in 0 until n) if (keep[i]) out.add(points[i])
        return out
    }
}
