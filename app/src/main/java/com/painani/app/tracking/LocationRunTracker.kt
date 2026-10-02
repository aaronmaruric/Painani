package com.painani.app.tracking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.painani.app.domain.model.Split
import com.painani.app.domain.model.TrackPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * GPS-driven [RunTracker] built directly on [LocationManager] (no Play Services), in the same
 * spirit as RunnerUp's tracker. Filters poor fixes, accumulates distance between accepted
 * fixes, cuts a split every [splitDistanceMeters], and keeps a rolling-window pace.
 *
 * Runs on the main looper; callers must hold ACCESS_FINE_LOCATION before calling [start].
 */
class LocationRunTracker(
    private val context: Context,
    private val splitDistanceMeters: Double = 1000.0,
) : RunTracker {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val _state = MutableStateFlow(RunState())
    override val state: StateFlow<RunState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tickJob: Job? = null

    // Elapsed-time bookkeeping in monotonic time, immune to wall-clock changes mid-run.
    // Null while the clock is not running: before the first fix, and while paused.
    private var segmentStartRealtime: Long? = null
    private var accumulatedBeforeSegment = 0L

    private var lastAccepted: Location? = null
    /** Elapsed time at the last accepted fix, so the gap to the next one can be credited as moving time. */
    private var lastFixElapsed = 0L
    private var splitStartMillis = 0L
    private val recentFixes = ArrayDeque<Pair<Long, Double>>() // (elapsedMillis, cumulativeMeters)

    private val listener = LocationListener { location -> onLocation(location) }

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    val isGpsEnabled: Boolean
        get() = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)

    override fun start() {
        if (_state.value.isActive) return
        if (!hasPermission) return
        reset()
        // The clock starts on the first usable fix, not now: a cold GPS start can take a minute
        // or more and that wait is not part of the run.
        _state.value = RunState(status = TrackerStatus.WAITING_FOR_FIX)
        requestUpdates()
        startTicker()
    }

    override fun pause() {
        if (_state.value.status != TrackerStatus.RUNNING && _state.value.status != TrackerStatus.WAITING_FOR_FIX) return
        accumulatedBeforeSegment = elapsedNow()
        segmentStartRealtime = null
        locationManager.removeUpdates(listener)
        tickJob?.cancel()
        // Forget the last fix so the jump on resume is not counted as distance or moving time.
        lastAccepted = null
        recentFixes.clear()
        _state.update { it.copy(status = TrackerStatus.PAUSED, elapsedMillis = accumulatedBeforeSegment, currentPaceSecPerKm = null) }
    }

    override fun resume() {
        if (_state.value.status != TrackerStatus.PAUSED) return
        segmentStartRealtime = SystemClock.elapsedRealtime()
        _state.update { it.copy(status = TrackerStatus.WAITING_FOR_FIX) }
        requestUpdates()
        startTicker()
    }

    override fun stop(): RunState {
        val elapsed = elapsedNow()
        locationManager.removeUpdates(listener)
        tickJob?.cancel()

        val s = _state.value
        val splits = s.completedSplits.toMutableList()
        val partialMillis = elapsed - splitStartMillis
        if (s.currentSplitMeters > 1.0 && partialMillis > 0) {
            splits += Split(index = splits.size, distanceMeters = s.currentSplitMeters, durationMillis = partialMillis)
        }
        val final = s.copy(
            status = TrackerStatus.IDLE,
            elapsedMillis = elapsed,
            completedSplits = splits,
            currentSplitMeters = 0.0,
            currentSplitMillis = 0,
            currentPaceSecPerKm = null,
        )
        _state.value = RunState()
        reset()
        return final
    }

    // --- internals ---------------------------------------------------------------------

    private fun reset() {
        lastAccepted = null
        lastFixElapsed = 0
        splitStartMillis = 0
        accumulatedBeforeSegment = 0
        segmentStartRealtime = null
        recentFixes.clear()
    }

    private fun elapsedNow(): Long =
        accumulatedBeforeSegment + (segmentStartRealtime?.let { SystemClock.elapsedRealtime() - it } ?: 0L)

    @Suppress("MissingPermission") // checked via hasPermission in start()
    private fun requestUpdates() {
        if (!hasPermission) return
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            MIN_TIME_MS,
            0f,
            listener,
            Looper.getMainLooper(),
        )
    }

    /** Keeps the clock moving between fixes so the UI ticks every second even standing still. */
    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                val elapsed = elapsedNow()
                _state.update { it.copy(elapsedMillis = elapsed, currentSplitMillis = elapsed - splitStartMillis) }
                delay(1000)
            }
        }
    }

    private fun onLocation(location: Location) {
        val status = _state.value.status
        if (status != TrackerStatus.RUNNING && status != TrackerStatus.WAITING_FOR_FIX) return
        if (!location.hasAccuracy() || location.accuracy > MAX_ACCURACY_M) {
            _state.update { it.copy(accuracyMeters = location.accuracy) }
            return
        }

        // First usable fix of the run: this is when the run (and its clock) starts.
        if (_state.value.startedAtMillis == 0L) {
            if (segmentStartRealtime == null) segmentStartRealtime = SystemClock.elapsedRealtime()
            _state.update { it.copy(startedAtMillis = System.currentTimeMillis()) }
        }

        val elapsed = elapsedNow()
        val point = TrackPoint(
            timeMillis = location.time,
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeMeters = if (location.hasAltitude()) location.altitude else null,
            accuracyMeters = location.accuracy,
        )

        val prev = lastAccepted
        lastAccepted = location
        if (prev == null) {
            // First fix of this segment: anchor only, no distance.
            lastFixElapsed = elapsed
            _state.update { it.copy(status = TrackerStatus.RUNNING, elapsedMillis = elapsed, accuracyMeters = location.accuracy, trackPoints = it.trackPoints + point) }
            recentFixes.addLast(elapsed to _state.value.distanceMeters)
            return
        }

        var step = prev.distanceTo(location).toDouble()
        // GPS jitter while standing still shows up as tiny random steps; ignore movement
        // smaller than the combined accuracy would justify.
        if (step < MIN_STEP_M) step = 0.0
        // Credit the gap since the last fix as moving time only if we actually moved, and cap it
        // so a long GPS dropout in a tunnel is not counted as running.
        val movingStep = if (step > 0) (elapsed - lastFixElapsed).coerceIn(0L, MAX_MOVING_GAP_MS) else 0L
        lastFixElapsed = elapsed

        // All of this runs on the main looper, so read-modify-write on the flow is race-free.
        _state.value = _state.value.let { s ->
            val total = s.distanceMeters + step
            var splitMeters = s.currentSplitMeters + step
            val splits = s.completedSplits.toMutableList()

            // A single step can (rarely) cross more than one split boundary if fixes were sparse.
            while (splitMeters >= splitDistanceMeters) {
                val overshoot = splitMeters - splitDistanceMeters
                // Interpolate the crossing time proportionally within this step.
                val fraction = if (step > 0) 1.0 - overshoot / step else 1.0
                val prevElapsed = recentFixes.lastOrNull()?.first ?: elapsed
                val crossElapsed = (prevElapsed + (elapsed - prevElapsed) * fraction).toLong()
                splits += Split(
                    index = splits.size,
                    distanceMeters = splitDistanceMeters,
                    durationMillis = crossElapsed - splitStartMillis,
                )
                splitStartMillis = crossElapsed
                splitMeters = overshoot
            }

            recentFixes.addLast(elapsed to total)
            while (recentFixes.size > 1 && elapsed - recentFixes.first().first > PACE_WINDOW_MS) recentFixes.removeFirst()
            val pace = recentFixes.firstOrNull()?.let { (t0, d0) ->
                val dt = (elapsed - t0) / 1000.0
                val dd = total - d0
                if (dd > 5.0 && dt > 5.0) dt / (dd / 1000.0) else null
            }

            s.copy(
                status = TrackerStatus.RUNNING,
                elapsedMillis = elapsed,
                movingMillis = s.movingMillis + movingStep,
                distanceMeters = total,
                completedSplits = splits,
                currentSplitMeters = splitMeters,
                currentSplitMillis = elapsed - splitStartMillis,
                currentPaceSecPerKm = pace,
                accuracyMeters = location.accuracy,
                trackPoints = s.trackPoints + point,
            )
        }
    }

    companion object {
        private const val MIN_TIME_MS = 1000L
        /** Fixes worse than this are dropped outright. */
        private const val MAX_ACCURACY_M = 40f
        /** Steps below this are treated as stationary noise. */
        private const val MIN_STEP_M = 1.5
        private const val PACE_WINDOW_MS = 30_000L
        /** Longest gap between fixes still credited as moving time. */
        private const val MAX_MOVING_GAP_MS = 10_000L
    }
}
