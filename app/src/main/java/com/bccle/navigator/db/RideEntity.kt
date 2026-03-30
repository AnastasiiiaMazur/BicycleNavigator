package com.bccle.navigator.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rides")
data class RideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val startLat: Double,
    val startLon: Double,
    val specLengthMeters: Int,
    val specProfile: String,
    val specSeed: Int,
    val specDir: String,
    val distanceMeters: Int,
    val durationSeconds: Long,
    val avgSpeedKmh: Double? = null,
    val difficulty: Int? = null,
    val description: String? = null,
    val rating: Int? = null,
    val createdAt: Long,
    val im1Uri: String? = null,
    val im2Uri: String? = null,
    val im3Uri: String? = null,

    val polylineJson: String? = null
)