package com.tomcollins.tracklog.data

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }
}

data class TrackStats(
    val distanceM: Double = 0.0,
    val totalTimeMs: Long = 0,
    val movingTimeMs: Long = 0,
    val maxSpeedMps: Double = 0.0,
    val elevationGainM: Double = 0.0,
    val elevationLossM: Double = 0.0,
    val minAltM: Double? = null,
    val maxAltM: Double? = null,
    val pointCount: Int = 0,
    val startTime: Long = 0,
    val endTime: Long = 0,
) {
    val avgSpeedMps: Double get() = if (totalTimeMs > 0) distanceM / (totalTimeMs / 1000.0) else 0.0
    val avgMovingSpeedMps: Double get() = if (movingTimeMs > 0) distanceM / (movingTimeMs / 1000.0) else 0.0
}

/**
 * Incrementally computes track statistics. Pure Kotlin so it's unit-testable.
 *
 * - Distance and time are only accumulated between points of the same segment (pauses are gaps).
 * - Moving time counts intervals where speed is above [movingThresholdMps].
 * - Elevation gain/loss smooths GPS altitude (moving average) and then applies hysteresis, so
 *   altitude noise — often ±10 m on phones without a barometer — doesn't inflate the totals.
 */
class StatsAccumulator(
    private val movingThresholdMps: Double = 0.5,
    private val elevationHysteresisM: Double = 8.0,
    private val altitudeWindow: Int = 10,
    private val maxPlausibleSpeedMps: Double = 120.0,
) {
    private var distance = 0.0
    private var totalTime = 0L
    private var movingTime = 0L
    private var maxSpeed = 0.0
    private var gain = 0.0
    private var loss = 0.0
    private var minAlt: Double? = null
    private var maxAlt: Double? = null
    private var count = 0
    private var startTime = 0L
    private var endTime = 0L

    private var lastTime = 0L
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastSegment = Int.MIN_VALUE
    private var altRef: Double? = null
    private val recentAlts = ArrayDeque<Double>()

    fun add(time: Long, lat: Double, lon: Double, alt: Double?, speed: Float?, segment: Int) {
        if (count == 0) startTime = time
        endTime = time
        if (count > 0 && segment == lastSegment) {
            val dt = time - lastTime
            val d = Geo.distance(lastLat, lastLon, lat, lon)
            if (dt > 0) {
                val v = d / (dt / 1000.0)
                distance += d
                totalTime += dt
                if (v >= movingThresholdMps) movingTime += dt
                val reported = speed?.toDouble()?.takeIf { it in 0.0..maxPlausibleSpeedMps }
                val candidate = reported ?: v.takeIf { it <= maxPlausibleSpeedMps } ?: 0.0
                maxSpeed = max(maxSpeed, candidate)
            }
        }
        if (alt != null) {
            recentAlts.addLast(alt)
            if (recentAlts.size > altitudeWindow) recentAlts.removeFirst()
            val smooth = recentAlts.average()
            minAlt = minAlt?.let { min(it, smooth) } ?: smooth
            maxAlt = maxAlt?.let { max(it, smooth) } ?: smooth
            val ref = altRef
            if (ref == null) {
                // Early GPS altitudes are often far off; start counting once the average has settled.
                if (recentAlts.size >= altitudeWindow) altRef = smooth
            } else {
                val diff = smooth - ref
                if (diff >= elevationHysteresisM) {
                    gain += diff; altRef = smooth
                } else if (diff <= -elevationHysteresisM) {
                    loss += -diff; altRef = smooth
                }
            }
        }
        lastTime = time
        lastLat = lat
        lastLon = lon
        lastSegment = segment
        count++
    }

    fun add(p: PointEntity) = add(p.time, p.lat, p.lon, p.alt, p.speed, p.segment)

    fun snapshot() = TrackStats(
        distanceM = distance,
        totalTimeMs = totalTime,
        movingTimeMs = movingTime,
        maxSpeedMps = maxSpeed,
        elevationGainM = gain,
        elevationLossM = loss,
        minAltM = minAlt,
        maxAltM = maxAlt,
        pointCount = count,
        startTime = startTime,
        endTime = endTime,
    )

    companion object {
        fun of(points: List<PointEntity>): TrackStats {
            val acc = StatsAccumulator()
            points.forEach { acc.add(it) }
            return acc.snapshot()
        }
    }
}

fun TrackEntity.withStats(s: TrackStats, totalTimeMs: Long = s.totalTimeMs) = copy(
    distanceM = s.distanceM,
    totalTimeMs = totalTimeMs,
    movingTimeMs = s.movingTimeMs,
    maxSpeedMps = s.maxSpeedMps,
    elevationGainM = s.elevationGainM,
    elevationLossM = s.elevationLossM,
    minAltM = s.minAltM,
    maxAltM = s.maxAltM,
    pointCount = s.pointCount,
    endTime = if (s.pointCount > 0) max(s.endTime, startTime) else endTime,
)

fun TrackEntity.stats() = TrackStats(
    distanceM, totalTimeMs, movingTimeMs, maxSpeedMps, elevationGainM, elevationLossM,
    minAltM, maxAltM, pointCount, startTime, endTime,
)
