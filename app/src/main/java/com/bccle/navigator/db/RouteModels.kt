package com.bccle.navigator.db

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class LatLngDto(val lat: Double, val lon: Double) : Parcelable

@Parcelize
data class RouteSpec(
    val start: LatLngDto,    // startCenter
    val lengthMeters: Int,   // distanceKm * 1000
    val profile: String,     // "cycling-regular" | "cycling-road" | "cycling-mountain"
    val seed: Int,           // 1 if not RANDOM
    val dir: String          // "CLOCKWISE" | "COUNTERCLOCKWISE" | "RANDOM"
) : Parcelable