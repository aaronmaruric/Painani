package com.painani.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.painani.app.data.local.entity.ExerciseEntity
import com.painani.app.data.local.entity.ExerciseSetEntity
import com.painani.app.data.local.entity.SessionEntity
import com.painani.app.data.local.entity.SessionWithDetails
import com.painani.app.data.local.entity.SplitEntity
import com.painani.app.data.local.entity.TrackPointEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Transaction
    @Query(
        "SELECT * FROM sessions WHERE startedAtEpochMillis >= :fromMillis AND startedAtEpochMillis < :toMillis " +
            "ORDER BY startedAtEpochMillis ASC"
    )
    fun sessionsBetween(fromMillis: Long, toMillis: Long): Flow<List<SessionWithDetails>>

    @Transaction
    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun session(id: Long): SessionWithDetails?

    @Upsert
    suspend fun upsertSession(session: SessionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSplits(splits: List<SplitEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSets(sets: List<ExerciseSetEntity>)

    @Query("DELETE FROM splits WHERE sessionId = :sessionId")
    suspend fun deleteSplitsFor(sessionId: Long)

    @Query("DELETE FROM exercise_sets WHERE sessionId = :sessionId")
    suspend fun deleteSetsFor(sessionId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrackPoints(points: List<TrackPointEntity>)

    @Query("DELETE FROM track_points WHERE sessionId = :sessionId")
    suspend fun deleteTrackPointsFor(sessionId: Long)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("SELECT sourceId FROM sessions WHERE sourceId IS NOT NULL")
    suspend fun sourceIds(): List<String>

    @Query("UPDATE sessions SET avgHeartRate = :avg, maxHeartRate = :max WHERE id = :id")
    suspend fun updateSessionHeartRate(id: Long, avg: Int?, max: Int?)

    @Query("UPDATE splits SET avgHeartRate = :avg WHERE sessionId = :sessionId AND `index` = :index")
    suspend fun updateSplitHeartRate(sessionId: Long, index: Int, avg: Int?)

    @Query("SELECT * FROM exercises ORDER BY name ASC")
    fun exercises(): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercises WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun exerciseByName(name: String): ExerciseEntity?

    @Upsert
    suspend fun upsertExercise(exercise: ExerciseEntity): Long
}
