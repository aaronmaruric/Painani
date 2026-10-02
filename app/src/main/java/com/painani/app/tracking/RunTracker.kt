package com.painani.app.tracking

import com.painani.app.domain.model.Split
import com.painani.app.domain.model.TrackPoint
import kotlinx.coroutines.flow.StateFlow

enum class TrackerStatus { IDLE, WAITING_FOR_FIX, RUNNING, PAUSED }

/** Live snapshot of an in-progress run, updated as GPS fixes arrive. */
data class RunState(
    val status: TrackerStatus = TrackerStatus.IDLE,
    /** Wall-clock start of the run (the first usable fix), epoch millis. 0 until then. */
    val startedAtMillis: Long = 0,
    /** Run time: from the first fix, excluding paused intervals. Ticks while standing still. */
    val elapsedMillis: Long = 0,
    /**
     * Time spent actually moving: the sum of gaps between fixes that advanced the distance.
     * Standing at a light or waiting for GPS to come back does not count.
     */
    val movingMillis: Long = 0,
    val distanceMeters: Double = 0.0,
    val completedSplits: List<Split> = emptyList(),
    /** Distance covered so far in the split currently in progress. */
    val currentSplitMeters: Double = 0.0,
    val currentSplitMillis: Long = 0,
    /** Pace over the last ~30 s, in seconds per km. Null until enough fixes exist. */
    val currentPaceSecPerKm: Double? = null,
    /** Accuracy of the most recent fix, metres. Null before the first fix. */
    val accuracyMeters: Float? = null,
    val trackPoints: List<TrackPoint> = emptyList(),
) {
    val isActive get() = status == TrackerStatus.RUNNING || status == TrackerStatus.PAUSED || status == TrackerStatus.WAITING_FOR_FIX

    /** Average moving pace, like a watch shows it: stops are excluded so it stays comparable with [currentPaceSecPerKm]. */
    val averagePaceSecPerKm: Double?
        get() = if (distanceMeters > 50 && movingMillis > 0) (movingMillis / 1000.0) / (distanceMeters / 1000.0) else null
}

/**
 * Abstraction over the GPS engine so the UI never touches LocationManager directly.
 * The Android implementation wraps LocationManager; an iOS port would wrap CoreLocation.
 */
interface RunTracker {
    val state: StateFlow<RunState>
    fun start()
    fun pause()
    fun resume()
    /** Stops tracking and returns the final state, with the in-progress split closed out. */
    fun stop(): RunState
}
