package com.bccle.navigator.fragments

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.bccle.navigator.R
import com.bccle.navigator.fragments.helpers.LocationKit
import com.bccle.navigator.fragments.helpers.LocationPermissionRequester
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File

class HomeFragment : Fragment(R.layout.fragment_home) {

    private lateinit var newRouteBtn: LinearLayout
    private lateinit var savedRidesBtn: LinearLayout
    private lateinit var statsBtn: LinearLayout
    private lateinit var manualRouteBtn: LinearLayout

    private lateinit var mapView: MapView
    private var myLocation: MyLocationNewOverlay? = null

    private lateinit var locationRequester: LocationPermissionRequester

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        newRouteBtn = view.findViewById(R.id.newRouteBtn)
        savedRidesBtn = view.findViewById(R.id.savedRidesBtn)
        statsBtn = view.findViewById(R.id.statsBtn)
        manualRouteBtn = view.findViewById(R.id.manualRouteBtn)

        newRouteBtn.setOnClickListener { findNavController().navigate(R.id.newRouteFragment) }
        savedRidesBtn.setOnClickListener { findNavController().navigate(R.id.savedRoutesFragment) }
        statsBtn.setOnClickListener { findNavController().navigate(R.id.statsFragment) }
        manualRouteBtn.setOnClickListener { findNavController().navigate(R.id.manualRouteFragment) }

        val base = File(requireContext().cacheDir, "osmdroid")
        val tiles = File(base, "tiles")
        org.osmdroid.config.Configuration.getInstance().apply {
            userAgentValue = requireContext().packageName
            osmdroidBasePath = base
            osmdroidTileCache = tiles
        }

        mapView = view.findViewById(R.id.mapView)
        mapView.setMultiTouchControls(true)
        mapView.setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)

        locationRequester = LocationPermissionRequester(
            fragment = this,
            onGranted = { initLocation() },
            onDenied = { permanentlyDenied ->
                if (permanentlyDenied) {
                    showLocationPermissionSettingsDialog()
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Location permission is required to show your position.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )

        checkPermsThenEnableLocation()
    }

    private fun checkPermsThenEnableLocation() {
        locationRequester.request()
    }

    private fun initLocation() {
        centerFromLastKnown()
        enableMyLocation()
    }

    private fun centerFromLastKnown() {
        val here: GeoPoint? = LocationKit.toGeoPoint(LocationKit.getBestLastKnownLocation(requireContext()))
        if (here != null) {
            mapView.controller.setZoom(15.0)
            mapView.controller.setCenter(here)
        }
    }

    private fun enableMyLocation() {
        if (myLocation != null) return
        myLocation = LocationKit.attachMyLocationOverlay(
            mapView = mapView,
            context = requireContext(),
            follow = true,
            minTimeMs = 1000L,
            minDistanceM = 2f
        ) { firstFix ->
            requireActivity().runOnUiThread {
                mapView.controller.animateTo(firstFix)
            }
        }
        myLocation?.let { mapView.overlays.add(it) }
    }

    private fun showLocationPermissionSettingsDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Location permission needed")
            .setMessage("To show your current location, allow location access in Settings.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Open settings") { _, _ ->
                LocationKit.openAppSettings(this)
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        // If user returned from Settings and granted permission, finish setup.
        if (LocationKit.hasLocationPermission(requireContext())) {
            initLocation()
        }
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onDestroyView() {
        myLocation?.disableMyLocation()
        myLocation = null
        super.onDestroyView()
    }
}