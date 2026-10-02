package com.painani.app.domain.repository

import com.painani.app.domain.model.Exercise
import com.painani.app.domain.model.Session
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for sessions. Implemented by the Room-backed repository in the data layer.
 * Kept free of Android types so it can move into a shared KMP module later.
 */
interface SessionRepository {
    /** All sessions whose start date falls within [from, to], inclusive. Emits on every change. */
    fun sessionsBetween(from: LocalDate, to: LocalDate): Flow<List<Session>>

    fun sessionsOn(date: LocalDate): Flow<List<Session>>

    suspend fun session(id: Long): Session?

    /** Inserts or updates the session and its children. Returns the session id. */
    suspend fun save(session: Session): Long

    suspend fun delete(id: Long)

    /** External record ids of every imported session, so a sync can skip what it already has. */
    suspend fun knownSourceIds(): Set<String>

    /** Updates only the heart-rate summary and per-split averages, leaving everything else untouched. */
    suspend fun updateHeartRate(id: Long, avg: Int?, max: Int?, splitAverages: List<Int?>)

    fun exercises(): Flow<List<Exercise>>

    suspend fun saveExercise(exercise: Exercise): Long
}
