package com.bccle.navigator.fragments.helpers

enum class UnitSystem { METRIC, IMPERIAL } // km / mi

object Units {
    private const val M_TO_KM = 0.001
    private const val M_TO_MI = 0.000621371
    private const val MPS_TO_KMH = 3.6
    private const val MPS_TO_MPH = 2.2369363

    fun metersToUserDistance(meters: Int, sys: UnitSystem): Double =
        if (sys == UnitSystem.IMPERIAL) meters * M_TO_MI else meters * M_TO_KM

    fun formatDistance(meters: Int, sys: UnitSystem): String {
        val v = metersToUserDistance(meters, sys)
        val unit = if (sys == UnitSystem.IMPERIAL) "mi" else "km"
        val fmt = if (v >= 10) "%.0f %s" else "%.1f %s"
        return String.format(java.util.Locale.US, fmt, v, unit)
    }

    fun formatSpeed(mps: Double, sys: UnitSystem): String {
        val v = if (sys == UnitSystem.IMPERIAL) mps * MPS_TO_MPH else mps * MPS_TO_KMH
        val unit = if (sys == UnitSystem.IMPERIAL) "mph" else "km/h"
        val fmt = if (v >= 10) "%.0f %s" else "%.1f %s"
        return String.format(java.util.Locale.US, fmt, v, unit)
    }

    /** Convert a “slider value” in user units to meters. */
    fun userDistanceToMeters(value: Int, sys: UnitSystem): Int =
        if (sys == UnitSystem.IMPERIAL)
            (value / M_TO_MI).toInt()
        else
            (value / M_TO_KM).toInt()
}