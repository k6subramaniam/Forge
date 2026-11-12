package com.forge.mobile.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

// Storage records for the phone, the source of truth (blueprint §4). Columns use
// plain types: instants as epoch milliseconds, dates as epoch days, enums by
// name. Mapping to the :core models lives in Mappers.kt.
//
// The HealthProfile is deliberately absent: it lives in an encrypted file, not
// in this database (see HealthProfileStore).

@Entity(tableName = "workout_sessions")
data class WorkoutSessionEntity(
    @PrimaryKey val id: String,
    val routineId: String?,
    val routineName: String?,
    val startTimeEpochMs: Long,
    val endTimeEpochMs: Long?,
    val notes: String?,
    val avgHeartRateBpm: Int?,
    val maxHeartRateBpm: Int?,
    val heartRateRecoveryBpm: Int?,
)

@Entity(
    tableName = "set_entries",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId"), Index("exerciseId")],
)
data class SetEntryEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val exerciseId: String,
    val exerciseName: String,
    val setNumber: Int,
    val weightKg: Double,
    val reps: Int,
    val completed: Boolean,
    val timestampEpochMs: Long,
    val rpe: Double?,
    val rir: Int?,
    val painReported: Boolean,
)

/** A session with its sets, read in one transaction. */
data class SessionWithSets(
    @Embedded val session: WorkoutSessionEntity,
    @Relation(parentColumn = "id", entityColumn = "sessionId")
    val sets: List<SetEntryEntity>,
)

/** The single goal profile (onboarding steps 1–2 and 5). Always row [SINGLETON_ID]. */
@Entity(tableName = "goal_profile")
data class GoalProfileEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val goal: String,
    val ageYears: Int,
    val sex: String,
    val heightCm: Double,
    val weightKg: Double,
    val trainingDaysPerWeek: Int,
    val experienceLevel: String,
    val equipmentAccess: String,
    val activityLevel: String,
    val calorieTargetKcal: Int?,
    val proteinTargetGrams: Int?,
    val carbTargetGrams: Int?,
    val fatTargetGrams: Int?,
    val dietaryPattern: String?,
    /** JSON array of strings. */
    val dislikesJson: String,
    /** JSON array of strings. */
    val allergiesJson: String,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}

/** One weigh-in per day: a second weigh-in the same day replaces the first. */
@Entity(tableName = "weigh_ins")
data class WeighInEntity(
    @PrimaryKey val epochDay: Long,
    val weightKg: Double,
)

/** A watch BIA reading with the conditions confirmed on the prompt; QC is applied on read. */
@Entity(tableName = "bia_readings", indices = [Index("takenAtLocal")])
data class BiaReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Local wall-clock time, yyyy-MM-ddTHH:mm:ss (fixed width, so it sorts). "Morning" means local time. */
    val takenAtLocal: String,
    val fatPercent: Double,
    val fasted: Boolean,
    val postVoid: Boolean,
    val usualHydration: Boolean,
)

@Entity(tableName = "tape_measurements", indices = [Index("epochDay")])
data class TapeMeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val neckCm: Double?,
    val waistAtNavelCm: Double?,
    val naturalWaistCm: Double?,
    val waistAtIliacCrestCm: Double?,
    val hipCm: Double?,
)

@Entity(tableName = "dexa_scans", indices = [Index("epochDay")])
data class DexaScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val scannerBrand: String,
    val scannerSite: String,
    val fatPercent: Double,
    val fatMassKg: Double?,
    val leanSoftTissueKg: Double?,
    val boneMineralContentKg: Double?,
)
