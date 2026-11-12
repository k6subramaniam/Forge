package com.forge.mobile.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class SessionDao {

    // @Upsert updates in place. A REPLACE insert would delete the old session
    // row first, and the foreign-key cascade would take its sets with it.
    @Upsert
    abstract suspend fun upsertSession(session: WorkoutSessionEntity)

    @Upsert
    abstract suspend fun upsertSets(sets: List<SetEntryEntity>)

    /** Writes a session and its sets atomically: both land or neither does. */
    @Transaction
    open suspend fun upsertSessionWithSets(session: WorkoutSessionEntity, sets: List<SetEntryEntity>) {
        upsertSession(session)
        upsertSets(sets)
    }

    /** Every session with its sets, newest first; re-emits on any change. */
    @Transaction
    @Query("SELECT * FROM workout_sessions ORDER BY startTimeEpochMs DESC")
    abstract fun observeSessionsWithSets(): Flow<List<SessionWithSets>>

    @Query("SELECT * FROM set_entries WHERE sessionId = :sessionId ORDER BY setNumber")
    abstract suspend fun setsFor(sessionId: String): List<SetEntryEntity>

    @Query("SELECT * FROM workout_sessions WHERE id = :id")
    abstract suspend fun session(id: String): WorkoutSessionEntity?

    /** The most recent completed set of an exercise: the watch's ghost-target source. */
    @Query(
        "SELECT * FROM set_entries WHERE exerciseId = :exerciseId AND completed = 1 " +
            "ORDER BY timestampEpochMs DESC LIMIT 1",
    )
    abstract suspend fun lastCompletedSet(exerciseId: String): SetEntryEntity?
}

@Dao
interface GoalProfileDao {
    @Upsert
    suspend fun upsert(profile: GoalProfileEntity)

    @Query("SELECT * FROM goal_profile WHERE id = ${GoalProfileEntity.SINGLETON_ID}")
    suspend fun get(): GoalProfileEntity?
}

@Dao
interface WeighInDao {
    @Upsert
    suspend fun upsert(weighIn: WeighInEntity)

    @Query("SELECT * FROM weigh_ins ORDER BY epochDay")
    fun observeAll(): Flow<List<WeighInEntity>>

    @Query("SELECT * FROM weigh_ins ORDER BY epochDay")
    suspend fun all(): List<WeighInEntity>
}

@Dao
interface BodyCompositionDao {
    @Insert
    suspend fun insertBia(reading: BiaReadingEntity): Long

    @Insert
    suspend fun insertTape(measurement: TapeMeasurementEntity): Long

    @Insert
    suspend fun insertDexa(scan: DexaScanEntity): Long

    @Query("SELECT * FROM bia_readings ORDER BY takenAtLocal")
    suspend fun allBia(): List<BiaReadingEntity>

    @Query("SELECT * FROM tape_measurements ORDER BY epochDay, id")
    suspend fun allTape(): List<TapeMeasurementEntity>

    @Query("SELECT * FROM dexa_scans ORDER BY epochDay, id")
    suspend fun allDexa(): List<DexaScanEntity>
}
