package com.painani.app.data.repository

import androidx.room.withTransaction
import com.painani.app.data.local.PainaniDatabase
import com.painani.app.data.local.entity.ExerciseEntity
import com.painani.app.data.local.entity.ExerciseSetEntity
import com.painani.app.data.local.entity.ExerciseSetWithExercise
import com.painani.app.data.local.entity.SessionEntity
import com.painani.app.data.local.entity.SessionWithDetails
import com.painani.app.data.local.entity.SplitEntity
import com.painani.app.data.local.entity.TrackPointEntity
import com.painani.app.domain.model.Exercise
import com.painani.app.domain.model.ExerciseSet
import com.painani.app.domain.model.Session
import com.painani.app.domain.model.SessionType
import com.painani.app.domain.model.Split
import com.painani.app.domain.model.TrackPoint
import com.painani.app.domain.repository.SessionRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomSessionRepository(
    private val db: PainaniDatabase,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : SessionRepository {

    private val dao get() = db.sessionDao()

    override fun sessionsBetween(from: LocalDate, to: LocalDate): Flow<List<Session>> {
        val fromMillis = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val toMillis = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return dao.sessionsBetween(fromMillis, toMillis).map { rows -> rows.map { it.toDomain() } }
    }

    override fun sessionsOn(date: LocalDate): Flow<List<Session>> = sessionsBetween(date, date)

    override suspend fun session(id: Long): Session? = dao.session(id)?.toDomain()

    override suspend fun save(session: Session): Long = db.withTransaction {
        // Upsert returns -1 when it updated an existing row rather than inserting.
        val id = dao.upsertSession(session.toEntity()).let { if (it == -1L) session.id else it }
        dao.deleteSplitsFor(id)
        dao.deleteSetsFor(id)
        dao.deleteTrackPointsFor(id)
        dao.insertSplits(session.splits.map { it.toEntity(id) })
        dao.insertSets(session.sets.map { it.toEntity(id) })
        dao.insertTrackPoints(session.trackPoints.map { it.toEntity(id) })
        id
    }

    override suspend fun delete(id: Long) = dao.deleteSession(id)

    override suspend fun knownSourceIds(): Set<String> = dao.sourceIds().toSet()

    override suspend fun updateHeartRate(id: Long, avg: Int?, max: Int?, splitAverages: List<Int?>) = db.withTransaction {
        dao.updateSessionHeartRate(id, avg, max)
        splitAverages.forEachIndexed { index, hr -> dao.updateSplitHeartRate(id, index, hr) }
    }

    override fun exercises(): Flow<List<Exercise>> =
        dao.exercises().map { rows -> rows.map { it.toDomain() } }

    override suspend fun saveExercise(exercise: Exercise): Long {
        // Exercises are identified by name from the UI, so reuse an existing row when one matches.
        if (exercise.id == 0L) {
            dao.exerciseByName(exercise.name)?.let { return it.id }
        }
        val id = dao.upsertExercise(ExerciseEntity(exercise.id, exercise.name))
        return if (id == -1L) exercise.id else id
    }

    // --- mappers ---------------------------------------------------------------------------

    private fun SessionWithDetails.toDomain() = Session(
        id = session.id,
        type = SessionType.valueOf(session.type),
        startedAt = Instant.ofEpochMilli(session.startedAtEpochMillis),
        durationMillis = session.durationMillis,
        notes = session.notes,
        distanceMeters = session.distanceMeters,
        splits = splits.sortedBy { it.index }.map { it.toDomain() },
        sets = sets.sortedBy { it.set.setIndex }.map { it.toDomain() },
        trackPoints = trackPoints.sortedBy { it.timeMillis }.map { it.toDomain() },
        avgHeartRate = session.avgHeartRate,
        maxHeartRate = session.maxHeartRate,
        sourceId = session.sourceId,
        source = session.source,
    )

    private fun Session.toEntity() = SessionEntity(
        id = id,
        type = type.name,
        startedAtEpochMillis = startedAt.toEpochMilli(),
        durationMillis = durationMillis,
        notes = notes,
        distanceMeters = distanceMeters,
        avgHeartRate = avgHeartRate,
        maxHeartRate = maxHeartRate,
        sourceId = sourceId,
        source = source,
    )

    private fun SplitEntity.toDomain() = Split(id, index, distanceMeters, durationMillis, avgHeartRate)

    private fun Split.toEntity(sessionId: Long) =
        SplitEntity(id, sessionId, index, distanceMeters, durationMillis, avgHeartRate)

    private fun ExerciseEntity.toDomain() = Exercise(id, name)

    private fun TrackPointEntity.toDomain() =
        TrackPoint(timeMillis, latitude, longitude, altitudeMeters, accuracyMeters)

    private fun TrackPoint.toEntity(sessionId: Long) =
        TrackPointEntity(0, sessionId, timeMillis, latitude, longitude, altitudeMeters, accuracyMeters)

    private fun ExerciseSetWithExercise.toDomain() =
        ExerciseSet(set.id, exercise.toDomain(), set.setIndex, set.reps, set.weightKg)

    private fun ExerciseSet.toEntity(sessionId: Long) =
        ExerciseSetEntity(id, sessionId, exercise.id, setIndex, reps, weightKg)
}
