package com.tomcollins.tracklog.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsTest {
    @Test
    fun haversineOneDegreeOfLatitude() {
        assertEquals(111_195.0, Geo.distance(0.0, 0.0, 1.0, 0.0), 50.0)
    }

    @Test
    fun distanceAndTimeAccumulate() {
        val acc = StatsAccumulator()
        // ~111 m per 0.001 deg latitude, every 60 s
        for (i in 0..10) acc.add(i * 60_000L, 53.0 + i * 0.001, -6.0, 100.0, null, 0)
        val s = acc.snapshot()
        assertEquals(1112.0, s.distanceM, 5.0)
        assertEquals(600_000L, s.totalTimeMs)
        assertEquals(600_000L, s.movingTimeMs)
        assertEquals(11, s.pointCount)
    }

    @Test
    fun pausesAreNotCounted() {
        val acc = StatsAccumulator()
        acc.add(0, 53.0, -6.0, null, null, 0)
        acc.add(60_000, 53.001, -6.0, null, null, 0)
        // resume in a new segment far away an hour later
        acc.add(3_600_000, 53.5, -6.0, null, null, 1)
        acc.add(3_660_000, 53.501, -6.0, null, null, 1)
        val s = acc.snapshot()
        assertEquals(2 * 111.2, s.distanceM, 2.0)
        assertEquals(120_000L, s.totalTimeMs)
    }

    @Test
    fun standingStillIsNotMoving() {
        val acc = StatsAccumulator()
        for (i in 0..10) acc.add(i * 10_000L, 53.0, -6.0, null, null, 0)
        assertEquals(0L, acc.snapshot().movingTimeMs)
        assertEquals(100_000L, acc.snapshot().totalTimeMs)
    }

    @Test
    fun altitudeNoiseWhileStandingStillIsIgnored() {
        val acc = StatsAccumulator()
        val rnd = java.util.Random(7)
        for (i in 0 until 300) acc.add(i * 2000L, 53.0, -6.0, 100.0 + rnd.nextGaussian() * 6.0, null, 0)
        val s = acc.snapshot()
        assertTrue("gain ${s.elevationGainM}", s.elevationGainM < 15)
        assertTrue("loss ${s.elevationLossM}", s.elevationLossM < 15)
    }

    @Test
    fun realClimbIsCountedDespiteNoise() {
        val acc = StatsAccumulator()
        val rnd = java.util.Random(3)
        // climb 200 m over 400 points, then descend 200 m
        for (i in 0 until 400) acc.add(i * 5000L, 53.0 + i * 0.0001, -6.0, 100.0 + i * 0.5 + rnd.nextGaussian() * 4.0, null, 0)
        for (i in 0 until 400) acc.add((400 + i) * 5000L, 53.04 + i * 0.0001, -6.0, 300.0 - i * 0.5 + rnd.nextGaussian() * 4.0, null, 0)
        val s = acc.snapshot()
        assertEquals(200.0, s.elevationGainM, 25.0)
        assertEquals(200.0, s.elevationLossM, 25.0)
    }

    @Test
    fun implausibleSpeedSpikesIgnoredForMax() {
        val acc = StatsAccumulator()
        acc.add(0, 53.0, -6.0, null, 1.5f, 0)
        acc.add(1000, 53.00001, -6.0, null, 500f, 0) // bogus reported speed
        assertTrue(acc.snapshot().maxSpeedMps < 10)
    }
}
