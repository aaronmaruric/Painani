package com.painani.app

import com.painani.app.domain.model.TrackPoint
import com.painani.app.domain.track.TrackMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMathTest {

    /** Points heading due north at a steady 1 m/s; one degree of latitude is ~111.2 km. */
    private fun straightNorth(seconds: Int, metersPerSecond: Double = 1.0): List<TrackPoint> =
        (0..seconds).map { t ->
            TrackPoint(timeMillis = t * 1000L, latitude = t * metersPerSecond / 111_195.0, longitude = 0.0)
        }

    @Test
    fun `haversine matches a known short distance`() {
        val a = TrackPoint(0, 0.0, 0.0)
        val b = TrackPoint(0, 0.001, 0.0)
        assertEquals(111.2, TrackMath.distanceMeters(a, b), 0.2)
    }

    @Test
    fun `length sums consecutive steps`() {
        assertEquals(100.0, TrackMath.lengthMeters(straightNorth(100)), 0.1)
    }

    @Test
    fun `splits cut whole kilometres plus a remainder`() {
        val splits = TrackMath.splits(straightNorth(2500, metersPerSecond = 1.0))
        assertEquals(3, splits.size)
        assertEquals(1000.0, splits[0].distanceMeters, 0.01)
        assertEquals(1000.0, splits[1].distanceMeters, 0.01)
        assertEquals(500.0, splits[2].distanceMeters, 1.0)
        // Steady 1 m/s means each full km takes 1000 s.
        assertEquals(1000_000.0, splits[0].durationMillis.toDouble(), 1500.0)
        assertEquals(1000_000.0, splits[1].durationMillis.toDouble(), 1500.0)
        assertEquals(listOf(0, 1, 2), splits.map { it.index })
    }

    @Test
    fun `too few points give no splits`() {
        assertTrue(TrackMath.splits(emptyList()).isEmpty())
        assertTrue(TrackMath.splits(straightNorth(0)).isEmpty())
    }
}
