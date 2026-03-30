package com.bccle.navigator.fragments.extra

import android.app.AlertDialog
import com.bccle.navigator.BuildConfig
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.bccle.navigator.R
import com.bccle.navigator.db.AppDatabase
import com.bccle.navigator.db.LatLngDto
import com.bccle.navigator.db.RideEntity
import com.bccle.navigator.db.RouteSpec
import com.bccle.navigator.fragments.helpers.OrsDirectionsBody
import com.bccle.navigator.fragments.helpers.OrsOptions
import com.bccle.navigator.fragments.helpers.OrsRoundTrip
import com.bccle.navigator.fragments.helpers.UnitSystem
import com.bccle.navigator.fragments.helpers.Units
import com.bccle.navigator.fragments.helpers.UserPrefs
import com.bccle.navigator.fragments.helpers.installHideKeyboardOnTouchOutside
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.bccle.navigator.fragments.helpers.OrsClient

class SavedDetailsFragment : Fragment(R.layout.fradment_saved_details) {

    companion object {
        private const val ARG_RIDE_ID = "arg_ride_id"
    }

    private lateinit var rideTitle: TextView
    private lateinit var rideTitleEdit: EditText
    private lateinit var dateTv: TextView
    private lateinit var distanceDurationTv: TextView
    private lateinit var starsContainer: LinearLayout
    private lateinit var descriptionEt: EditText
    private lateinit var im1: ImageView
    private lateinit var im2: ImageView
    private lateinit var im3: ImageView
    private lateinit var saveBtn: TextView
    private lateinit var editBtn: TextView
    private lateinit var deleteBtn: TextView
    private lateinit var mapView: MapView
    private lateinit var im1Delete: ImageView
    private lateinit var im2Delete: ImageView
    private lateinit var im3Delete: ImageView

    private var isEditing = false
    private var currentRating = 0
    private var image1Uri: String? = null
    private var image2Uri: String? = null
    private var image3Uri: String? = null
    private var selectedImageSlot = 0
    private lateinit var ride: RideEntity

    private val ors by lazy { OrsClient.create(logging = false) }
    private var routeJob: Job? = null
    private var routePolyline: Polyline? = null

    private var currentUnit: UnitSystem = UnitSystem.METRIC
    private var unitCollectorJob: Job? = null

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (!::ride.isInitialized) return@registerForActivityResult
        uri ?: return@registerForActivityResult

        when (selectedImageSlot) {
            1 -> deleteLocalFileIfAny(image1Uri)
            2 -> deleteLocalFileIfAny(image2Uri)
            3 -> deleteLocalFileIfAny(image3Uri)
        }

        val fileName = "ride_${ride.id}_slot${selectedImageSlot}_${System.currentTimeMillis()}.jpg"
        val saved = copyToAppFiles(uri, fileName)

        when (selectedImageSlot) {
            1 -> { image1Uri = saved.toString(); im1.setImageURI(saved) }
            2 -> { image2Uri = saved.toString(); im2.setImageURI(saved) }
            3 -> { image3Uri = saved.toString(); im3.setImageURI(saved) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rideTitle = view.findViewById(R.id.rideTitle)
        rideTitleEdit = view.findViewById(R.id.rideTitleEdit)
        dateTv = view.findViewById(R.id.date)
        distanceDurationTv = view.findViewById(R.id.distanceDuration)
        starsContainer = view.findViewById(R.id.stars)
        descriptionEt = view.findViewById(R.id.description)
        im1 = view.findViewById(R.id.im1)
        im2 = view.findViewById(R.id.im2)
        im3 = view.findViewById(R.id.im3)
        saveBtn = view.findViewById(R.id.saveBtn)
        editBtn = view.findViewById(R.id.edit)
        deleteBtn = view.findViewById(R.id.delete)
        mapView = view.findViewById(R.id.mapView)
        im1Delete = view.findViewById(R.id.im1Delete)
        im2Delete = view.findViewById(R.id.im2Delete)
        im3Delete = view.findViewById(R.id.im3Delete)

        view.isClickable = true
        view.isFocusableInTouchMode = true
        view.installHideKeyboardOnTouchOutside()

        // Observe unit system
        unitCollectorJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                UserPrefs.unitFlow(requireContext()).collect { unit ->
                    currentUnit = unit
                    if (::ride.isInitialized) applyDistanceAndDuration()
                }
            }
        }

        // osmdroid init
        val base = requireContext().cacheDir.resolve("osmdroid")
        Configuration.getInstance().apply {
            userAgentValue = requireContext().packageName
            osmdroidBasePath = base
            osmdroidTileCache = base.resolve("tiles")
        }
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(false)

        // load ride by id then bind + draw route
        val rideId = requireArguments().getLong(ARG_RIDE_ID)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.getInstance(requireContext()).rideDao()
            val loaded = dao.getById(rideId)
            withContext(Dispatchers.Main) {
                if (loaded == null) {
                    Toast.makeText(requireContext(), "Ride not found", Toast.LENGTH_SHORT).show()
                    findNavController().popBackStack()
                } else {
                    ride = loaded
                    bindRide()
                    val spec = RouteSpec(
                        start = LatLngDto(ride.startLat, ride.startLon),
                        lengthMeters = ride.specLengthMeters,
                        profile = ride.specProfile,
                        seed = ride.specSeed,
                        dir = ride.specDir
                    )
                    fetchAndDraw(spec)
                }
            }
        }

        // actions
        editBtn.setOnClickListener { toggleEditMode(true) }
        saveBtn.setOnClickListener { if (isEditing) saveEdits() }

        deleteBtn.setOnClickListener {
            if (!::ride.isInitialized) return@setOnClickListener
            showConfirmDeleteDialog(
                onYes = {
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        deleteLocalFileIfAny(image1Uri)
                        deleteLocalFileIfAny(image2Uri)
                        deleteLocalFileIfAny(image3Uri)
                        AppDatabase.getInstance(requireContext()).rideDao().delete(ride.id)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "Ride Deleted", Toast.LENGTH_SHORT).show()
                            findNavController().navigateUp()
                        }
                    }
                }
            )
        }

        // image taps (only in edit mode)
        im1.setOnClickListener {
            if (!isEditing || !::ride.isInitialized) return@setOnClickListener
            selectedImageSlot = 1; pickImage.launch("image/*")
        }
        im2.setOnClickListener {
            if (!isEditing || !::ride.isInitialized) return@setOnClickListener
            selectedImageSlot = 2; pickImage.launch("image/*")
        }
        im3.setOnClickListener {
            if (!isEditing || !::ride.isInitialized) return@setOnClickListener
            selectedImageSlot = 3; pickImage.launch("image/*")
        }

        im1Delete.setOnClickListener {
            if (!isEditing) return@setOnClickListener
            deleteLocalFileIfAny(image1Uri); image1Uri = null; im1.setImageResource(R.drawable.ic_camera)
        }
        im2Delete.setOnClickListener {
            if (!isEditing) return@setOnClickListener
            deleteLocalFileIfAny(image2Uri); image2Uri = null; im2.setImageResource(R.drawable.ic_camera)
        }
        im3Delete.setOnClickListener {
            if (!isEditing) return@setOnClickListener
            deleteLocalFileIfAny(image3Uri); image3Uri = null; im3.setImageResource(R.drawable.ic_camera)
        }
    }

    private fun bindRide() {
        val titleText = if (ride.name.isNotBlank()) ride.name else "Route ${ride.id}"
        rideTitle.text = titleText
        rideTitleEdit.setText(titleText)

        dateTv.text = formatRideDate(ride.createdAt, longMonth = true)
        applyDistanceAndDuration() // <- unit-aware

        currentRating = ride.rating ?: 0
        inflateStars()
        renderStars(currentRating)

        descriptionEt.setText(ride.description ?: "")
        descriptionEt.isEnabled = false

        image1Uri = ride.im1Uri
        image2Uri = ride.im2Uri
        image3Uri = ride.im3Uri
        setImageFromDb(im1, image1Uri)
        setImageFromDb(im2, image2Uri)
        setImageFromDb(im3, image3Uri)

        toggleEditMode(false)
    }

    private fun applyDistanceAndDuration() {
        val dist = Units.formatDistance(ride.distanceMeters, currentUnit)
        distanceDurationTv.text = "$dist, ${formatDuration(ride.durationSeconds)}"
    }

    // --- Images helpers ---
    private fun copyToAppFiles(src: Uri, fileName: String): Uri {
        val dir = File(requireContext().filesDir, "images").apply { mkdirs() }
        val outFile = File(dir, fileName)
        requireContext().contentResolver.openInputStream(src).use { input ->
            FileOutputStream(outFile).use { output -> input?.copyTo(output) }
        }
        return Uri.fromFile(outFile)
    }

    private fun deleteLocalFileIfAny(uriStr: String?) {
        val u = uriStr?.let { Uri.parse(it) } ?: return
        if (u.scheme == "file") File(u.path ?: "").takeIf { it.exists() }?.delete()
    }

    private fun setImageFromDb(view: ImageView, uriStr: String?) {
        if (uriStr.isNullOrBlank()) view.setImageResource(R.drawable.ic_camera)
        else view.setImageURI(Uri.parse(uriStr))
    }

    // --- Stars ---
    private fun inflateStars() {
        starsContainer.removeAllViews()
        repeat(5) { idx ->
            val iv = ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                adjustViewBounds = true
                setImageResource(R.drawable.star_empty)
                setPadding(6, 0, 6, 0)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (!isEditing) return@setOnClickListener
                    currentRating = idx + 1
                    renderStars(currentRating)
                }
            }
            starsContainer.addView(iv)
        }
    }

    private fun renderStars(rating: Int) {
        for (i in 0 until starsContainer.childCount) {
            val iv = starsContainer.getChildAt(i) as ImageView
            iv.setImageResource(if (i < rating) R.drawable.star_filled else R.drawable.star_empty)
        }
    }

    // --- Edit/Save ---
    private fun toggleEditMode(editing: Boolean) {
        isEditing = editing

        rideTitleEdit.visibility = if (editing) View.VISIBLE else View.GONE
        saveBtn.visibility = if (editing) View.VISIBLE else View.GONE
        editBtn.visibility = if (editing) View.GONE else View.VISIBLE
        deleteBtn.visibility = if (editing) View.GONE else View.VISIBLE

        im1Delete.visibility = if (editing) View.VISIBLE else View.GONE
        im2Delete.visibility = if (editing) View.VISIBLE else View.GONE
        im3Delete.visibility = if (editing) View.VISIBLE else View.GONE

        descriptionEt.isEnabled = editing

        for (i in 0 until starsContainer.childCount) {
            starsContainer.getChildAt(i).isEnabled = editing
        }
    }

    private fun saveEdits() {
        val newName = rideTitleEdit.text?.toString()?.trim().orEmpty()
        val newDesc = descriptionEt.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.getInstance(requireContext()).rideDao()
            dao.updateEditableFields(
                id = ride.id,
                name = newName,
                description = newDesc,
                rating = currentRating.takeIf { it in 1..5 },
                im1 = image1Uri,
                im2 = image2Uri,
                im3 = image3Uri
            )
            val refreshed = dao.getById(ride.id)!!
            withContext(Dispatchers.Main) {
                ride = refreshed
                bindRide()
                Toast.makeText(requireContext(), "Saved", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- Delete dialog ---
    private fun showConfirmDeleteDialog(onYes: () -> Unit, onNo: (() -> Unit)? = null) {
        val dialogView = layoutInflater.inflate(R.layout.custom_alert_dialog, null)
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        dialogView.findViewById<TextView>(R.id.message).setText(R.string.delete_string)
        dialogView.findViewById<TextView>(R.id.yes).setOnClickListener { dialog.dismiss(); onYes() }
        dialogView.findViewById<TextView>(R.id.no).setOnClickListener { dialog.dismiss(); onNo?.invoke() }
    }

    // --- Fetch & draw saved route on this screen ---
    private fun fetchAndDraw(spec: RouteSpec) {
        val apiKey = BuildConfig.ORS_API_KEY
        routeJob?.cancel()
        routeJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
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
                val resp = withContext(Dispatchers.IO) {
                    ors.routeGeoJson((apiKey), spec.profile, body)
                }

                var pts = resp.features.firstOrNull()?.geometry?.coordinates.orEmpty()
                    .map { (lon, lat) -> GeoPoint(lat, lon) }
                if (spec.dir == "COUNTERCLOCKWISE") pts = pts.asReversed()

                drawPolylineWithStart(pts)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Route error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun drawPolylineWithStart(points: List<GeoPoint>) {
        routePolyline?.let { mapView.overlays.remove(it) }
        routePolyline = null
        mapView.overlays.removeAll { it is Marker }

        val start = GeoPoint(ride.startLat, ride.startLon)
        val startMarker = Marker(mapView).apply {
            position = start
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            this.title = "Start"
        }
        mapView.overlays.add(startMarker)

        if (points.isNotEmpty()) {
            routePolyline = Polyline().apply {
                setPoints(points)
                outlinePaint.strokeWidth = 6f
                outlinePaint.color = 0xFFE53935.toInt()
            }
            mapView.overlays.add(routePolyline)

            val bbox: BoundingBox = BoundingBox.fromGeoPointsSafe(points)
            mapView.zoomToBoundingBox(bbox, true, 80)
        } else {
            mapView.controller.setZoom(13.0)
            mapView.controller.setCenter(start)
        }
        mapView.invalidate()
    }

    // MapView lifecycle
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onDestroyView() {
        routeJob?.cancel()
        unitCollectorJob?.cancel()
        super.onDestroyView()
    }

    // Formatting helpers
    private fun formatRideDate(epochMillis: Long, longMonth: Boolean = true): String {
        val pattern = if (longMonth) "d MMMM yyyy" else "d MMM yyyy"
        val sdf = SimpleDateFormat(pattern, Locale.getDefault())
        return sdf.format(Date(epochMillis))
    }
    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}