package com.tomcollins.tracklog.ui

import com.tomcollins.tracklog.data.Units
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Format {
    private const val M_PER_MILE = 1609.344
    private const val FT_PER_M = 3.28084

    fun distance(m: Double, u: Units): String = when (u) {
        Units.METRIC -> if (m < 1000) "${m.toInt()} m" else String.format(Locale.getDefault(), "%.2f km", m / 1000)
        Units.IMPERIAL -> {
            val mi = m / M_PER_MILE
            if (mi < 0.1) "${(m * FT_PER_M).toInt()} ft" else String.format(Locale.getDefault(), "%.2f mi", mi)
        }
    }

    fun distanceValue(m: Double, u: Units) = if (u == Units.METRIC) m / 1000 else m / M_PER_MILE
    fun distanceUnit(u: Units) = if (u == Units.METRIC) "km" else "mi"

    fun speed(mps: Double, u: Units): String = when (u) {
        Units.METRIC -> String.format(Locale.getDefault(), "%.1f km/h", mps * 3.6)
        Units.IMPERIAL -> String.format(Locale.getDefault(), "%.1f mph", mps * 3600 / M_PER_MILE)
    }

    fun speedValue(mps: Double, u: Units) = if (u == Units.METRIC) mps * 3.6 else mps * 3600 / M_PER_MILE
    fun speedUnit(u: Units) = if (u == Units.METRIC) "km/h" else "mph"

    fun pace(mps: Double, u: Units): String {
        if (mps < 0.2) return "–"
        val secPerUnit = (if (u == Units.METRIC) 1000.0 else M_PER_MILE) / mps
        val min = (secPerUnit / 60).toInt()
        val sec = (secPerUnit % 60).toInt()
        return String.format(Locale.getDefault(), "%d:%02d /%s", min, sec, if (u == Units.METRIC) "km" else "mi")
    }

    fun elevation(m: Double?, u: Units): String = when {
        m == null -> "–"
        u == Units.METRIC -> "${m.toInt()} m"
        else -> "${(m * FT_PER_M).toInt()} ft"
    }

    fun elevationValue(m: Double, u: Units) = if (u == Units.METRIC) m else m * FT_PER_M
    fun elevationUnit(u: Units) = if (u == Units.METRIC) "m" else "ft"

    fun duration(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.getDefault(), "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }

    private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val fileFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")

    fun date(t: Long): String = dateFmt.format(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()))
    fun time(t: Long): String = timeFmt.format(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()))
    fun fileStamp(t: Long): String = fileFmt.format(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()))

    fun coords(lat: Double, lon: Double) = String.format(Locale.US, "%.5f, %.5f", lat, lon)
}
