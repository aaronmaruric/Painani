package com.painani.app.data.health

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.health.connect.client.records.ExerciseRoute
import com.painani.app.domain.model.Session
import com.painani.app.domain.model.SessionType
import com.painani.app.domain.model.WeightEntry
import com.painani.app.domain.repository.BodyStatsRepository
import com.painani.app.domain.repository.HealthDataRepository
import com.painani.app.domain.repository.SessionRepository
import com.painani.app.domain.track.TrackMath
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.syncStore: DataStore<Preferences> by preferencesDataStore(name = "health_sync")

/** How the scheduled background sync is configured. */
data class AutoSyncSettings(val enabled: Boolean = true, val intervalHours: Int = 3) {
    companion object {
        val INTERVALS = listOf(1, 3, 6)
    }
}

/**
 * What we do with Health Connect and when. Every call is best-effort: a missing permission or a
 * transient error is logged and swallowed, so the app never fails a save because of it.
 */
class HealthSync(
    private val context: Context,
    private val health: HealthConnectManager,
    private val sessions: SessionRepository,
    private val bodyStats: BodyStatsRepository,
    private val healthData: HealthDataRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()

    private val lastWeightPull = longPreferencesKey("last_weight_pull_epoch_ms")
    private val lastSessionPull = longPreferencesKey("last_session_pull_epoch_ms")
    private val lastSync = longPreferencesKey("last_sync_epoch_ms")
    /** How many days back the daily cache has been filled, so a later history grant triggers a deeper pull. */
    private val dailyBackfillDays = intPreferencesKey("daily_backfill_days")
    private val autoEnabled = booleanPreferencesKey("auto_sync_enabled")
    private val autoInterval = intPreferencesKey("auto_sync_interval_hours")

    val lastSyncTime = context.syncStore.data.map { p -> p[lastSync]?.let { Instant.ofEpochMilli(it) } }

    val autoSyncSettings: Flow<AutoSyncSettings> = context.syncStore.data.map { p ->
        AutoSyncSettings(
            enabled = p[autoEnabled] ?: true,
            intervalHours = p[autoInterval] ?: 3,
        )
    }

    /** Persists the schedule and (re)registers the periodic worker to match. */
    suspend fun setAutoSync(settings: AutoSyncSettings) {
        context.syncStore.edit {
            it[autoEnabled] = settings.enabled
            it[autoInterval] = settings.intervalHours
        }
        schedule(settings)
    }

    /** Called at app start so the worker reflects whatever was saved last time. */
    fun ensureScheduled() {
        scope.launch {
            runCatching { schedule(autoSyncSettings.first()) }.onFailure { Log.w(TAG, "schedule failed", it) }
        }
    }

    /** No point waking up on a Health Connect build that refuses background reads. */
    private fun schedule(settings: AutoSyncSettings) {
        val effective = settings.copy(enabled = settings.enabled && health.supportsBackgroundRead)
        HealthSyncWorker.schedule(context, effective)
    }

    /** Fire-and-forget: called right after a session row is written. */
    fun onSessionSaved(id: Long) {
        scope.launch {
            if (!health.hasAllPermissions()) return@launch
            val session = sessions.session(id) ?: return@launch
            runCatching { enrichWithHeartRate(session) }.onFailure { Log.w(TAG, "HR enrich failed", it) }
            if (session.isImported) return@launch // came from Health Connect; do not echo it back
            val refreshed = sessions.session(id) ?: session
            runCatching { health.writeSession(refreshed) }.onFailure { Log.w(TAG, "write session failed", it) }
            touch()
        }
    }

    fun onWeightLogged(entry: WeightEntry) {
        if (entry.sourceId != null) return // came from Health Connect; do not echo it back
        scope.launch {
            if (!health.hasAllPermissions()) return@launch
            runCatching { health.writeWeight(entry) }.onFailure { Log.w(TAG, "write weight failed", it) }
            touch()
        }
    }

    /**
     * Foreground refresh, called when the app comes to the front. Skipped when the last sync
     * was recent so opening the app repeatedly does not hammer Health Connect.
     */
    fun refreshIfStale(maxAge: Duration = Duration.ofMinutes(15)) {
        scope.launch {
            val last = lastSyncTime.first()
            if (last != null && Duration.between(last, Instant.now()) < maxAge) return@launch
            runCatching { syncNow() }.onFailure { Log.w(TAG, "foreground refresh failed", it) }
        }
    }

    /**
     * Full sync: daily readouts (steps, sleep, resting HR ...), weigh-ins and workouts from other
     * apps, and heart rate back-filled onto any run that does not have it yet. Returns a short summary.
     */
    suspend fun syncNow(): String = syncMutex.withLock {
        if (!health.hasAllPermissions()) return "Not connected"
        var pulled = 0
        var imported = 0
        var enriched = 0
        var days = 0

        runCatching { days = pullDaily() }.onFailure { Log.w(TAG, "daily pull failed", it) }

        runCatching {
            val since = context.syncStore.data.first()[lastWeightPull]?.let { Instant.ofEpochMilli(it) }
                ?: Instant.now().minus(Duration.ofDays(365))
            val known = bodyStats.knownSourceIds()
            health.externalWeights(since).filter { it.recordId !in known }.forEach { w ->
                bodyStats.addWeight(
                    WeightEntry(at = w.time, weightKg = w.kg, note = originLabel(w.origin), sourceId = w.recordId)
                )
                pulled++
            }
            context.syncStore.edit { it[lastWeightPull] = Instant.now().minus(Duration.ofDays(1)).toEpochMilli() }
        }.onFailure { Log.w(TAG, "weight pull failed", it) }

        runCatching { imported = importSessions() }.onFailure { Log.w(TAG, "workout import failed", it) }

        runCatching {
            val cutoff = LocalDate.now().minusDays(30)
            sessions.sessionsBetween(cutoff, LocalDate.now()).first()
                .filter { it.type == SessionType.RUN && it.avgHeartRate == null }
                .forEach { if (enrichWithHeartRate(it)) enriched++ }
        }.onFailure { Log.w(TAG, "HR back-fill failed", it) }

        touch()
        buildString {
            append("Synced")
            if (days > 0) append(" · $days day${if (days == 1) "" else "s"}")
            if (pulled > 0) append(" · $pulled weigh-in${if (pulled == 1) "" else "s"}")
            if (imported > 0) append(" · $imported workout${if (imported == 1) "" else "s"}")
            if (enriched > 0) append(" · HR on $enriched run${if (enriched == 1) "" else "s"}")
        }
    }

    /**
     * Refreshes the daily cache. The first time it reaches back a year (or 30 days if Health
     * Connect will not let us read history); after that only the last few days, which is enough
     * to pick up a watch that synced late.
     */
    private suspend fun pullDaily(): Int {
        val today = LocalDate.now()
        val wanted = if (health.hasHistoryRead()) BACKFILL_DAYS_HISTORY else BACKFILL_DAYS_DEFAULT
        val done = context.syncStore.data.first()[dailyBackfillDays] ?: 0
        val from = today.minusDays(if (done >= wanted) RECENT_DAYS else wanted.toLong())
        val days = health.dailySummaries(from, today)
        healthData.upsert(days)
        if (done < wanted) context.syncStore.edit { it[dailyBackfillDays] = wanted }
        return days.size
    }

    /**
     * Pulls workouts other apps recorded (a watch via Samsung Health, Garmin ...) into the log.
     * The first pull reaches back a year when Health Connect allows history reads, 30 days
     * otherwise; later pulls overlap the last one by a day so a watch that synced late is caught.
     * Record ids are remembered, so nothing is imported twice. Returns how many were added.
     */
    private suspend fun importSessions(): Int {
        val now = Instant.now()
        val since = context.syncStore.data.first()[lastSessionPull]?.let { Instant.ofEpochMilli(it) }
            ?: now.minus(Duration.ofDays(if (health.hasHistoryRead()) 365 else 30))
        val known = sessions.knownSourceIds()
        var added = 0
        health.externalSessions(since, now).filter { it.recordId !in known }.forEach { ext ->
            val label = originLabel(ext.origin)
            val session = Session(
                type = ext.type,
                startedAt = ext.start,
                durationMillis = Duration.between(ext.start, ext.end).toMillis(),
                notes = listOfNotNull(ext.title?.takeIf { it.isNotBlank() && !it.equals(ext.activity, true) }, ext.notes?.takeIf { it.isNotBlank() })
                    .joinToString("\n"),
                distanceMeters = ext.distanceMeters,
                splits = ext.splits.ifEmpty { TrackMath.splits(ext.trackPoints) },
                trackPoints = ext.trackPoints,
                sourceId = ext.recordId,
                source = "$label · ${ext.activity}",
            )
            val id = sessions.save(session)
            runCatching { enrichWithHeartRate(session.copy(id = id)) }.onFailure { Log.w(TAG, "HR enrich failed", it) }
            added++
        }
        context.syncStore.edit { it[lastSessionPull] = now.minus(Duration.ofDays(1)).toEpochMilli() }
        return added
    }

    /**
     * Stores a route the user released through the Health Connect consent dialog (see
     * [HealthConnectManager.routeRequestContract]) on an imported run, deriving km splits from
     * it when the source recorded no laps.
     */
    suspend fun attachRoute(sessionId: Long, route: ExerciseRoute) {
        val session = sessions.session(sessionId) ?: return
        val points = route.toTrackPoints()
        if (points.size < 2) return
        val updated = session.copy(
            trackPoints = points,
            splits = session.splits.ifEmpty { TrackMath.splits(points) },
            distanceMeters = session.distanceMeters ?: TrackMath.lengthMeters(points).takeIf { it > 0 },
        )
        sessions.save(updated)
        if (session.splits.isEmpty() && updated.splits.isNotEmpty()) {
            runCatching { enrichWithHeartRate(updated) }.onFailure { Log.w(TAG, "HR enrich failed", it) }
        }
        touch()
    }

    /** Reads HR for the session window and stores the average/max plus a per-split average. */
    private suspend fun enrichWithHeartRate(session: Session): Boolean {
        val start = session.startedAt
        val end = start.plusMillis(session.durationMillis)
        val samples = health.heartRate(start, end)
        if (samples.isEmpty()) return false

        val avg = samples.map { it.bpm }.average().toInt()
        val max = samples.maxOf { it.bpm }

        // Split boundaries in wall-clock time. Pauses are not modelled, so this is approximate
        // for runs with long stops; still far better than nothing.
        var cursor = start
        val splitAverages = session.splits.map { split ->
            val splitEnd = cursor.plusMillis(split.durationMillis)
            val inWindow = samples.filter { it.time >= cursor && it.time < splitEnd }
            cursor = splitEnd
            inWindow.takeIf { it.isNotEmpty() }?.map { it.bpm }?.average()?.toInt()
        }
        sessions.updateHeartRate(session.id, avg, max, splitAverages)
        return true
    }

    private suspend fun touch() {
        context.syncStore.edit { it[lastSync] = System.currentTimeMillis() }
    }

    private fun originLabel(pkg: String): String = when {
        pkg.contains("samsung") -> "Samsung Health"
        pkg.contains("fitbit") -> "Fitbit"
        pkg.contains("garmin") -> "Garmin"
        pkg.contains("withings") -> "Withings"
        pkg.contains("google") -> "Google"
        else -> "Health Connect"
    }

    companion object {
        private const val TAG = "HealthSync"
        private const val RECENT_DAYS = 3L
        private const val BACKFILL_DAYS_HISTORY = 365
        private const val BACKFILL_DAYS_DEFAULT = 30
    }
}
