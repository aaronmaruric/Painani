package com.painani.app.domain.track

import com.painani.app.domain.model.Split
import com.painani.app.domain.model.TrackPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Geometry over a recorded GPS trace. Pure Kotlin so it can be unit-tested and shared. */
object TrackMath {

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Great-circle distance between two fixes, metres. */
    fun distanceMeters(a: TrackPoint, b: TrackPoint): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Total path length of the trace, metres. */
    fun lengthMeters(points: List<TrackPoint>): Double =
        points.zipWithNext { a, b -> distanceMeters(a, b) }.sum()

    /**
     * Cuts the trace into whole [splitMeters] splits plus a partial last one, interpolating the
     * crossing time inside the step that crosses each boundary. Used for imported runs whose
     * source shared a route but no laps.
     */
    fun splits(points: List<TrackPoint>, splitMeters: Double = 1000.0): List<Split> {
        if (points.size < 2) return emptyList()
        val out = mutableListOf<Split>()
        var splitStartMillis = points.first().timeMillis
        var inSplit = 0.0
        for ((a, b) in points.zipWithNext()) {
            val step = distanceMeters(a, b)
            if (step <= 0) continue
            var stepRemaining = step
            var stepStartMillis = a.timeMillis
            val stepMillis = b.timeMillis - a.timeMillis
            while (inSplit + stepRemaining >= splitMeters) {
                val needed = splitMeters - inSplit
                val crossMillis = stepStartMillis + (stepMillis * (needed / step)).toLong()
                out += Split(index = out.size, distanceMeters = splitMeters, durationMillis = crossMillis - splitStartMillis)
                splitStartMillis = crossMillis
                stepStartMillis = crossMillis
                stepRemaining -= needed
                inSplit = 0.0
            }
            inSplit += stepRemaining
        }
        val tailMillis = points.last().timeMillis - splitStartMillis
        if (inSplit > 1.0 && tailMillis > 0) {
            out += Split(index = out.size, distanceMeters = inSplit, durationMillis = tailMillis)
        }
        return out
    }
}
