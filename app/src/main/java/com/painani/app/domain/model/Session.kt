package com.painani.app.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class SessionType { RUN, STRENGTH }

/**
 * A single training session, either a run or a strength workout.
 * This is the unit the calendar renders; type-specific detail lives in [splits] or [sets].
 */
data class Session(
    val id: Long = 0,
    val type: SessionType,
    val startedAt: Instant,
    val durationMillis: Long,
    val notes: String = "",
    /** Total distance in metres. Only meaningful for [SessionType.RUN]. */
    val distanceMeters: Double? = null,
    val splits: List<Split> = emptyList(),
    val sets: List<ExerciseSet> = emptyList(),
    /** Raw GPS trace for a run. Empty for manual entries and strength sessions. */
    val trackPoints: List<TrackPoint> = emptyList(),
    /** Heart-rate summary, filled from Health Connect when available. */
    val avgHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    /** Id of the external record this came from (e.g. a Health Connect session), so imports do not duplicate. */
    val sourceId: String? = null,
    /** Where an imported session came from, for display: "Samsung Health", "Garmin" ... Null for our own. */
    val source: String? = null,
) {
    val isImported: Boolean get() = sourceId != null

    fun localDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        startedAt.atZone(zone).toLocalDate()
}

/** A single GPS fix along a run. */
data class TrackPoint(
    val timeMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val accuracyMeters: Float? = null,
)

/** One lap/split of a run — typically per km or per mile. */
data class Split(
    val id: Long = 0,
    val index: Int,
    val distanceMeters: Double,
    val durationMillis: Long,
    val avgHeartRate: Int? = null,
) {
    /** Pace in seconds per kilometre. */
    val paceSecPerKm: Double
        get() = if (distanceMeters > 0) (durationMillis / 1000.0) / (distanceMeters / 1000.0) else 0.0
}

data class Exercise(
    val id: Long = 0,
    val name: String,
)

/** One set of a strength exercise, e.g. 8 reps @ 60 kg. */
data class ExerciseSet(
    val id: Long = 0,
    val exercise: Exercise,
    val setIndex: Int,
    val reps: Int,
    val weightKg: Double,
)
