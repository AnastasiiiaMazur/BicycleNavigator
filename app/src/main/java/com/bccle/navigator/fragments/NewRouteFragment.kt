package com.bccle.navigator.fragments

import android.os.Bundle
import com.bccle.navigator.BuildConfig
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.bccle.navigator.R
import com.bccle.navigator.db.LatLngDto
import com.bccle.navigator.db.RouteSpec
import com.bccle.navigator.fragments.helpers.LocationKit
import com.bccle.navigator.fragments.helpers.LocationPermissionRequester
import com.bccle.navigator.fragments.helpers.OrsDirectionsBody
import com.bccle.navigator.fragments.helpers.OrsOptions
import com.bccle.navigator.fragments.helpers.OrsRoundTrip
import com.bccle.navigator.fragments.helpers.UnitSystem
import com.bccle.navigator.fragments.helpers.Units
import com.bccle.navigator.fragments.helpers.UserPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import kotlin.math.cos
import kotlin.math.hypot
import com.bccle.navigator.fragments.helpers.OrsClient
import com.bccle.navigator.hasInternetConnection
import com.bccle.navigator.isInternetAvailable

class NewRouteFragment : Fragment(R.layout.fragment_new_route) {

    // Views
    private lateinit var kmSeekBar: SeekBar
    private lateinit var startLocation: LinearLayout
    private lateinit var startManualLocation: LinearLayout
    private lateinit var twistyRoute: LinearLayout
    private lateinit var scenicRoute: LinearLayout
    private lateinit var flatRoute: LinearLayout
    private lateinit var clockwiseRoute: LinearLayout
    private lateinit var counterclockwiseRoute: LinearLayout
    private lateinit var randomRoute: LinearLayout
    private lateinit var startRouteBtn: TextView

    // Map + location
    private lateinit var mapView: MapView
    private lateinit var locationRequester: LocationPermissionRequester
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private var startCenter: GeoPoint? = null
    private var startMarker: Marker? = null
    private var mapTapOverlay: MapEventsOverlay? = null

    // Drawn route
    private var routePolyline: Polyline? = null
    private var previewPoints: List<GeoPoint> = emptyList()

    // Routing state
    private val ors by lazy { OrsClient.create(logging = false) }
    private var routeJob: Job? = null
    private var isRouting: Boolean = false            // <— NEW
    private var pendingStart: Boolean = false         // <— NEW
    private var randomSeed = 1

    // UI state
    private enum class StartOption { MY_LOCATION, MANUAL }
    private enum class RouteType { TWISTY, SCENIC, FLAT }
    private enum class Direction { CLOCKWISE, COUNTERCLOCKWISE, RANDOM }

    private var selectedStart = StartOption.MY_LOCATION
    private var selectedType = RouteType.TWISTY
    private var selectedDir = Direction.CLOCKWISE

    // Slider value in *user* units (km or mi)
    private var distanceUnits = 0

    // Unit system (default METRIC)
    private var currentUnit: UnitSystem = UnitSystem.METRIC

    // SeekBar popup
    private lateinit var kmPopup: PopupWindow
    private lateinit var kmPopupText: TextView

    companion object {
        const val ARG_ROUTE_SPEC = "arg_route_spec"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        kmSeekBar = view.findViewById(R.id.kmSeekBar)
        startLocation = view.findViewById(R.id.startLocation)
        startManualLocation = view.findViewById(R.id.startManualLocation)
        mapView = view.findViewById(R.id.map)
        twistyRoute = view.findViewById(R.id.twistyRoute)
        scenicRoute = view.findViewById(R.id.scenicRoute)
        flatRoute = view.findViewById(R.id.flatRoute)
        clockwiseRoute = view.findViewById(R.id.clockwiseRoute)
        counterclockwiseRoute = view.findViewById(R.id.counterclockwiseRoute)
        randomRoute = view.findViewById(R.id.randomRoute)
        startRouteBtn = view.findViewById(R.id.startRouteBtn)

        // osmdroid
        val base = requireContext().cacheDir.resolve("osmdroid")
        Configuration.getInstance().apply {
            userAgentValue = requireContext().packageName
            osmdroidBasePath = base
            osmdroidTileCache = base.resolve("tiles")
        }
        mapView.setMultiTouchControls(true)
        mapView.setTileSource(TileSourceFactory.MAPNIK)

        setupUiGroups()
        setupSeekbar()
        observeUnitSystem()

        startRouteBtn.setOnClickListener {
            // Guard: only allow when route is ready
            if (!startRouteBtn.isEnabled) {
                pendingStart = true
                Toast.makeText(requireContext(), "Wait until the route is ready", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            navigateToNavigator()
        }

        // Location / my-location overlay
        locationRequester = LocationPermissionRequester(
            fragment = this,
            onGranted = {
                LocationKit.toGeoPoint(LocationKit.getBestLastKnownLocation(requireContext()))
                    ?.let {
                        startCenter = it
                        mapView.controller.setZoom(15.0)
                        mapView.controller.setCenter(it)
                        requestRoute()
                        updateStartBtnState()
                    }
                myLocationOverlay = LocationKit.attachMyLocationOverlay(
                    mapView = mapView,
                    context = requireContext(),
                    follow = true
                ) { firstFix ->
                    if (!isAdded) return@attachMyLocationOverlay
                    requireActivity().runOnUiThread {
                        startCenter = firstFix
                        mapView.controller.animateTo(firstFix)
                        requestRoute()
                        updateStartBtnState()
                    }
                }
                mapView.overlays.add(myLocationOverlay)
            },
            onDenied = { permanentlyDenied ->
                if (permanentlyDenied) LocationKit.openAppSettings(this)
                else Toast.makeText(
                    requireContext(),
                    "Location permission is needed for My Location.",
                    Toast.LENGTH_LONG
                ).show()
            }
        )
        locationRequester.request()
    }

    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause()  { mapView.onPause(); super.onPause() }

    override fun onDestroyView() {
        routeJob?.cancel()
        myLocationOverlay?.disableMyLocation()
        myLocationOverlay = null
        pendingStart = false
        isRouting = false

        startMarker?.let { mapView.overlays.remove(it) }; startMarker = null
        mapTapOverlay?.let { mapView.overlays.remove(it) }; mapTapOverlay = null
        routePolyline?.let { mapView.overlays.remove(it) }; routePolyline = null
        super.onDestroyView()
    }

    // ---------- Unit system ----------
    private fun observeUnitSystem() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                UserPrefs.unitFlow(requireContext()).collect { unit ->
                    currentUnit = unit
                    applyUnitToSeekbar()
                    requestRoute(debounce = true)
                }
            }
        }
    }

    private fun applyUnitToSeekbar() {
        val newMax = if (currentUnit == UnitSystem.IMPERIAL) 62 else 100
        val old = kmSeekBar.progress
        kmSeekBar.max = newMax
        kmSeekBar.progress = old.coerceAtMost(newMax)
        distanceUnits = kmSeekBar.progress
        if (kmPopup.isShowing) showAndPositionKm(kmSeekBar.progress)
        updateStartBtnState()
    }

    // ---------- UI groups ----------
    private fun setupUiGroups() {
        setupSingleSelect(listOf(startLocation, startManualLocation), 0) { id ->
            if (id == R.id.startLocation) {
                selectedStart = StartOption.MY_LOCATION
                exitManualStartMode()
                myLocationOverlay?.enableFollowLocation()
                myLocationOverlay?.myLocation?.let { startCenter = GeoPoint(it.latitude, it.longitude) }
                requestRoute()
            } else {
                selectedStart = StartOption.MANUAL
                myLocationOverlay?.disableFollowLocation()
                enterManualStartMode()
                Toast.makeText(requireContext(), "Tap the map (or drag the pin) to set the start", Toast.LENGTH_SHORT).show()
            }
            updateStartBtnState()
        }
        setupSingleSelect(listOf(twistyRoute, scenicRoute, flatRoute), 0) { id ->
            selectedType = when (id) {
                R.id.twistyRoute -> RouteType.TWISTY
                R.id.scenicRoute -> RouteType.SCENIC
                else -> RouteType.FLAT
            }
            requestRoute()
        }
        setupSingleSelect(listOf(clockwiseRoute, counterclockwiseRoute, randomRoute), 0) { id ->
            selectedDir = when (id) {
                R.id.clockwiseRoute -> Direction.CLOCKWISE
                R.id.counterclockwiseRoute -> Direction.COUNTERCLOCKWISE
                else -> Direction.RANDOM
            }
            if (selectedDir == Direction.RANDOM) randomSeed = (1..100000).random()
            requestRoute()
        }
    }

    private fun enterManualStartMode() {
        if (mapTapOverlay == null) {
            mapTapOverlay = MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean { p?.let { setManualStart(it) }; return true }
                override fun longPressHelper(p: GeoPoint?) = false
            })
            mapView.overlays.add(0, mapTapOverlay)
        }
        if (startMarker == null) {
            startMarker = Marker(mapView).apply {
                title = "Start"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                isDraggable = true
                setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                    override fun onMarkerDragStart(marker: Marker?) {}
                    override fun onMarkerDrag(marker: Marker?) {}
                    override fun onMarkerDragEnd(marker: Marker?) { marker?.position?.let { setManualStart(it) } }
                })
            }
            mapView.overlays.add(startMarker)
        }
        if (startCenter == null) {
            val mc = mapView.mapCenter
            startCenter = GeoPoint(mc.latitude, mc.longitude)
        }
        startMarker?.position = startCenter
        mapView.invalidate()
    }

    private fun exitManualStartMode() {
        mapTapOverlay?.let { mapView.overlays.remove(it) }; mapTapOverlay = null
        startMarker?.let { mapView.overlays.remove(it) }; startMarker = null
        mapView.invalidate()
    }

    private fun setManualStart(p: GeoPoint) {
        startCenter = p
        startMarker?.position = p
        mapView.controller.animateTo(p)
        requestRoute()
        updateStartBtnState()
    }

    private fun setupSingleSelect(items: List<LinearLayout>, defaultIndex: Int, onSelected: (selectedLayoutId: Int) -> Unit) {
        var selectedIndex = defaultIndex.coerceIn(0, items.lastIndex)
        items.forEachIndexed { i, row ->
            setRowSelected(row, i == selectedIndex)
            row.setOnClickListener {
                if (i == selectedIndex) return@setOnClickListener
                setRowSelected(items[selectedIndex], false)
                setRowSelected(row, true)
                selectedIndex = i
                onSelected(row.id)
            }
        }
        onSelected(items[selectedIndex].id)
    }

    private fun setRowSelected(container: LinearLayout, selected: Boolean) {
        (container.getChildAt(0) as? ImageView)?.isEnabled = selected
    }

    // ---------- SeekBar + popup ----------
    private fun setupSeekbar() {
        kmSeekBar.max = if (currentUnit == UnitSystem.IMPERIAL) 62 else 100
        kmSeekBar.progress = 0
        distanceUnits = 0

        kmPopupText = TextView(requireContext()).apply {
            textSize = 16f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setTextColor(0xFF000000.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt()); cornerRadius = dp(12).toFloat()
                setStroke(dp(1), 0xFFCCCCCC.toInt())
            }
            elevation = dp(4).toFloat()
        }
        kmPopup = PopupWindow(kmPopupText, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false)

        kmSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(sb: SeekBar) { showAndPositionKm(sb.progress) }
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) showAndPositionKm(p)
                distanceUnits = p
                requestRoute(debounce = true)
                updateStartBtnState()
            }
            override fun onStopTrackingTouch(sb: SeekBar) { kmPopup.dismiss() }
        })
    }

    private fun showAndPositionKm(progress: Int) {
        kmPopupText.text = "$progress ${if (currentUnit == UnitSystem.IMPERIAL) "mi" else "km"}"
        kmPopupText.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popW = kmPopupText.measuredWidth

        val sb = kmSeekBar
        val fraction = if (sb.max == 0) 0f else progress.toFloat() / sb.max
        val available = sb.width - sb.paddingLeft - sb.paddingRight
        val thumbCenterX = sb.paddingLeft + (available * fraction).toInt()

        val xOff = (thumbCenterX - popW / 2).coerceIn(0, sb.width - popW)
        val yOff = dp(8)

        if (kmPopup.isShowing) kmPopup.update(sb, xOff, yOff, -1, -1)
        else kmPopup.showAsDropDown(sb, xOff, yOff, Gravity.START)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun updateStartBtnState() {
        val enabled = (distanceUnits > 0 && startCenter != null && previewPoints.isNotEmpty() && !isRouting)
        startRouteBtn.isEnabled = enabled
        startRouteBtn.alpha = if (enabled) 1f else 0.5f
    }

    // ---------- Routing preview ----------
    private fun requestRoute(debounce: Boolean = false) {
        val center = startCenter ?: run {
            previewPoints = emptyList()
            drawPolyline(emptyList())
            updateStartBtnState()
            return
        }
        val lengthMeters = Units.userDistanceToMeters(distanceUnits, currentUnit)
        if (lengthMeters <= 0) {
            previewPoints = emptyList()
            drawPolyline(emptyList())
            updateStartBtnState()
            return
        }

        routeJob?.cancel()
        routeJob = viewLifecycleOwner.lifecycleScope.launch {
            if (debounce) delay(250)

            val profile = when (selectedType) {
                RouteType.SCENIC -> "cycling-regular"
                RouteType.FLAT   -> "cycling-road"
                RouteType.TWISTY -> "cycling-mountain"
            }
            val seed = if (selectedDir == Direction.RANDOM) randomSeed else 1

            isRouting = true
            previewPoints = emptyList()
            drawPolyline(emptyList())
            updateStartBtnState()

            if (!isInternetAvailable(requireContext())) {
                isRouting = false
                updateStartBtnState()
                Toast.makeText(
                    requireContext(),
                    "No internet connection. Turn on Wi-Fi or mobile data to generate a route.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            try {
                val body = OrsDirectionsBody(
                    coordinates = listOf(listOf(center.longitude, center.latitude)),
                    options = OrsOptions(
                        roundTrip = OrsRoundTrip(
                            length = lengthMeters,
                            seed = seed
                        )
                    )
                )
                //val apiKey = requireContext().manifestMetaString().orEmpty()
                val apiKey = BuildConfig.ORS_API_KEY
                val resp = withContext(Dispatchers.IO) { ors.routeGeoJson(apiKey, profile, body) }

                var pts = resp.features.firstOrNull()?.geometry?.coordinates
                    .orEmpty()
                    .map { ll -> GeoPoint(ll[1], ll[0]) }
                if (selectedDir == Direction.COUNTERCLOCKWISE) pts = pts.asReversed()

                val simplified = withContext(Dispatchers.Default) { simplifyForMap(pts).let { capPoints(it, 400) } }
                previewPoints = simplified
                drawPolyline(simplified)
            } catch (_: CancellationException) {
                // no-op
            } catch (e: Exception) {
                val msg = if (!hasInternetConnection(requireContext()))
                    "No internet connection. Turn on Wi-Fi or mobile data to generate a route."
                else
                    "Routing failed. Please try again."
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()

                previewPoints = emptyList()
                drawPolyline(emptyList())
            } finally {
                isRouting = false
                updateStartBtnState()

                if (pendingStart && startRouteBtn.isEnabled) {
                    pendingStart = false
                    navigateToNavigator()
                }
            }
        }
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

    private fun navigateToNavigator() {
        val center = startCenter ?: run {
            Toast.makeText(requireContext(), "Tap the map to set a start point", Toast.LENGTH_SHORT).show()
            return
        }
        val profile = when (selectedType) {
            RouteType.SCENIC -> "cycling-regular"
            RouteType.FLAT   -> "cycling-road"
            RouteType.TWISTY -> "cycling-mountain"
        }
        val seed = if (selectedDir == Direction.RANDOM) randomSeed else 1
        val lengthMeters = Units.userDistanceToMeters(distanceUnits, currentUnit)

        myLocationOverlay?.disableFollowLocation()

        val spec = RouteSpec(
            start = LatLngDto(center.latitude, center.longitude),
            lengthMeters = lengthMeters,
            profile = profile,
            seed = seed,
            dir = selectedDir.name
        )

        findNavController().navigate(
            R.id.navigatorFragment,
            Bundle().apply { putParcelable(ARG_ROUTE_SPEC, spec) }
        )
    }

    // ---------- Simplify ----------
    private fun simplifyForMap(points: List<GeoPoint>, toleranceMeters: Double = 10.0, maxPoints: Int = 400): List<GeoPoint> {
        if (points.size <= 2) return points
        val dp = douglasPeucker(points, toleranceMeters)
        return capPoints(dp, maxPoints)
    }

    private fun capPoints(points: List<GeoPoint>, maxPoints: Int): List<GeoPoint> {
        if (points.size <= maxPoints) return points
        val step = kotlin.math.ceil(points.size / maxPoints.toDouble()).toInt()
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
            val clamped = t.coerceIn(0.0, 1.0)
            val px = x1 + clamped * dx; val py = y1 + clamped * dy
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