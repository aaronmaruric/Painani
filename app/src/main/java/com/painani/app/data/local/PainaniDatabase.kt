package com.painani.app.data.local

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.painani.app.data.local.dao.CalendarEventDao
import com.painani.app.data.local.dao.DailyHealthDao
import com.painani.app.data.local.dao.SessionDao
import com.painani.app.data.local.dao.WeightEntryDao
import com.painani.app.data.local.entity.CalendarEventEntity
import com.painani.app.data.local.entity.DailyHealthEntity
import com.painani.app.data.local.entity.ExerciseEntity
import com.painani.app.data.local.entity.ExerciseSetEntity
import com.painani.app.data.local.entity.SessionEntity
import com.painani.app.data.local.entity.SplitEntity
import com.painani.app.data.local.entity.TrackPointEntity
import com.painani.app.data.local.entity.WeightEntryEntity

@Database(
    entities = [
        SessionEntity::class,
        SplitEntity::class,
        ExerciseEntity::class,
        ExerciseSetEntity::class,
        TrackPointEntity::class,
        CalendarEventEntity::class,
        WeightEntryEntity::class,
        DailyHealthEntity::class,
    ],
    version = 6,
    exportSchema = true,
    autoMigrations = [
        // v1 -> v2: adds track_points and calendar_events. Pure additions, so Room derives it.
        AutoMigration(from = 1, to = 2),
        // v2 -> v3: adds weight_entries.
        AutoMigration(from = 2, to = 3),
        // v3 -> v4: heart-rate columns on sessions, sourceId on weight_entries.
        AutoMigration(from = 3, to = 4),
        // v4 -> v5: adds daily_health (cached Health Connect readouts).
        AutoMigration(from = 4, to = 5),
        // v5 -> v6: sourceId/source on sessions (workouts imported from Health Connect).
        AutoMigration(from = 5, to = 6),
    ],
)
abstract class PainaniDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun calendarEventDao(): CalendarEventDao
    abstract fun weightEntryDao(): WeightEntryDao
    abstract fun dailyHealthDao(): DailyHealthDao

    companion object {
        fun build(context: Context): PainaniDatabase =
            Room.databaseBuilder(context, PainaniDatabase::class.java, "odysseus.db")
                .build()
    }
}
