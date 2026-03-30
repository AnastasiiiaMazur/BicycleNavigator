package com.bccle.navigator.fragments.helpers

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import kotlin.math.max

object LocationKit {

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun isPermanentlyDenied(fragment: Fragment): Boolean {
        val ctx = fragment.requireContext()
        val fineDenied = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        val coarseDenied = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        val fineRationale = fragment.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarseRationale = fragment.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
        return fineDenied && coarseDenied && !fineRationale && !coarseRationale
    }

    fun openAppSettings(fragment: Fragment) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", fragment.requireContext().packageName, null)
        )
        fragment.startActivity(intent)
    }

    fun getBestLastKnownLocation(context: Context): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        var best: Location? = null
        for (p in providers) {
            val l = runCatching { lm.getLastKnownLocation(p) }.getOrNull()
            if (l != null && (best == null || l.time > best!!.time)) best = l
        }
        return best
    }

    fun toGeoPoint(loc: Location?): GeoPoint? =
        loc?.let { GeoPoint(it.latitude, it.longitude) }

    fun attachMyLocationOverlay(
        mapView: MapView,
        context: Context,
        follow: Boolean = true,
        minTimeMs: Long = 1000L,
        minDistanceM: Float = 2f,
        onFirstFix: (GeoPoint) -> Unit = {}
    ): MyLocationNewOverlay {
        val provider = GpsMyLocationProvider(context).apply {
            locationUpdateMinTime = max(0L, minTimeMs)
            locationUpdateMinDistance = max(0f, minDistanceM)
        }
        return MyLocationNewOverlay(provider, mapView).also { overlay ->
            overlay.enableMyLocation()
            if (follow) overlay.enableFollowLocation()
            overlay.runOnFirstFix {
                val p = overlay.myLocation ?: return@runOnFirstFix
                onFirstFix(GeoPoint(p.latitude, p.longitude))
            }
        }
    }
}

class LocationPermissionRequester(
    private val fragment: Fragment,
    private val onGranted: () -> Unit,
    private val onDenied: (permanentlyDenied: Boolean) -> Unit
) {
    private val launcher =
        fragment.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (granted) onGranted() else onDenied(LocationKit.isPermanentlyDenied(fragment))
        }

    fun request() {
        val ctx = fragment.requireContext()
        if (LocationKit.hasLocationPermission(ctx)) onGranted()
        else launcher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ))
    }
}