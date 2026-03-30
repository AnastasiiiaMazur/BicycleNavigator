package com.bccle.navigator.fragments

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.util.Locale
import com.bccle.navigator.R
import com.bccle.navigator.db.AppDatabase
import com.bccle.navigator.db.RideEntity
import com.bccle.navigator.fragments.helpers.UnitSystem
import com.bccle.navigator.fragments.helpers.UserPrefs

class SavedRoutesFragment : Fragment(R.layout.fragment_saved_routes) {

    private lateinit var ridesContainer: LinearLayout
    private val mapViews = mutableListOf<MapView>()

    private var unit: UnitSystem = UnitSystem.METRIC

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ridesContainer = view.findViewById(R.id.ridesContainer)

        val base = requireContext().cacheDir.resolve("osmdroid")
        Configuration.getInstance().apply {
            userAgentValue = requireContext().packageName
            osmdroidBasePath = base
            osmdroidTileCache = base.resolve("tiles")
        }

        // Observe unit preference and reload list whenever it changes
        viewLifecycleOwner.lifecycleScope.launch {
            UserPrefs.unitFlow(requireContext()).collectLatest { u ->
                unit = u
                loadRides()
            }
        }
    }

    private fun loadRides() {
        viewLifecycleOwner.lifecycleScope.launch {
            val rides = withContext(Dispatchers.IO) {
                AppDatabase.getInstance(requireContext()).rideDao().getAll()
            }
            populate(rides)
        }
    }

    private fun populate(rides: List<RideEntity>) {
        ridesContainer.removeAllViews()
        mapViews.clear()

        for (ride in rides) {
            val item = layoutInflater.inflate(R.layout.saved_ride_item, ridesContainer, false)

            val titleTv = item.findViewById<TextView>(R.id.rideTitle)
            val dateTv  = item.findViewById<TextView>(R.id.rideDate)
            val distTv  = item.findViewById<TextView>(R.id.rideDistance)
            val viewBtn = item.findViewById<TextView>(R.id.view)
            val delBtn  = item.findViewById<TextView>(R.id.delete)
            val map     = item.findViewById<MapView>(R.id.mapView)

            // map thumb
            map.setMultiTouchControls(false)
            map.setTileSource(TileSourceFactory.MAPNIK)
            val start = GeoPoint(ride.startLat, ride.startLon)
            map.controller.setZoom(15.0)
            map.controller.setCenter(start)
            Marker(map).apply {
                position = start
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "Start"
                map.overlays.add(this)
            }
            mapViews.add(map)

            // texts
            titleTv.text = if (ride.name.isNotBlank()) ride.name else "Route ${ride.id}"
            dateTv.text  = formatRideDate(ride.createdAt, longMonth = true)

            val dist = metersToUnit(ride.distanceMeters) // uses current 'unit'
            distTv.text = "${formatNum(dist)} ${distanceUnitLabel()}, ${formatDuration(ride.durationSeconds)}"

            viewBtn.setOnClickListener {
                val args = Bundle().apply { putLong("arg_ride_id", ride.id) }
                findNavController().navigate(R.id.savedRoutesDetailsFragment, args)
            }

            delBtn.setOnClickListener {
                showConfirmDeleteDialog(
                    onYes = {
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            AppDatabase.getInstance(requireContext()).rideDao().delete(ride.id)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(requireContext(), "Deleted", Toast.LENGTH_SHORT).show()
                                ridesContainer.removeView(item)
                            }
                        }
                    }
                )
            }

            ridesContainer.addView(item)
        }
    }

    // ---------- Lifecycle -> forward to all MapViews ----------
    override fun onResume() { super.onResume(); mapViews.forEach { it.onResume() } }
    override fun onPause()  { mapViews.forEach { it.onPause() }; super.onPause() }
    override fun onDestroyView() {
        mapViews.forEach { it.overlays.clear() }
        mapViews.clear()
        super.onDestroyView()
    }

    // ---------- Unit helpers (use current 'unit') ----------
    private fun metersToUnit(meters: Number): Double {
        val m = meters.toDouble()
        return if (unit == UnitSystem.IMPERIAL) m / 1609.344 else m / 1000.0
    }
    private fun distanceUnitLabel(): String =
        if (unit == UnitSystem.IMPERIAL) "mi" else "km"

    private fun formatNum(v: Double): String = String.format(Locale.US, "%.1f", v)

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    private fun formatRideDate(epochMillis: Long, longMonth: Boolean = false): String {
        val pattern = if (longMonth) "d MMMM yyyy" else "d MMM yyyy"
        val sdf = java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault())
        return sdf.format(java.util.Date(epochMillis))
    }

    private fun showConfirmDeleteDialog(
        onYes: () -> Unit,
        onNo: (() -> Unit)? = null
    ) {
        val dialogView = layoutInflater.inflate(R.layout.custom_alert_dialog, null)
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()

        dialogView.findViewById<TextView>(R.id.message).setText(R.string.delete_string)
        dialogView.findViewById<TextView>(R.id.yes).setOnClickListener { dialog.dismiss(); onYes() }
        dialogView.findViewById<TextView>(R.id.no).setOnClickListener  { dialog.dismiss(); onNo?.invoke() }
    }
}
