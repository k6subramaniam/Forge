package com.forge.core.model

import java.time.Instant
import java.time.LocalDate

/** Blueprint §5: a workout session. [routineId] is null for freeform sessions. */
data class WorkoutSession(
    val id: String,
    val routineId: String? = null,
    val routineName: String? = null,
    val startTime: Instant,
    val endTime: Instant? = null,
    val notes: String? = null,
    val avgHeartRateBpm: Int? = null,
    val maxHeartRateBpm: Int? = null,
    /** HR drop in the minute after the session ends (blueprint 3.1). */
    val heartRateRecoveryBpm: Int? = null,
) {
    val isCompleted: Boolean get() = endTime != null
}

/** Blueprint §5: one logged set inside a session. */
data class SetEntry(
    val id: String,
    val sessionId: String,
    val exerciseId: String,
    val exerciseName: String,
    val setNumber: Int,
    val weightKg: Double,
    val reps: Int,
    val completed: Boolean = true,
    val timestamp: Instant,
) {
    /** Set volume in kg (weight × reps). */
    val volumeKg: Double get() = weightKg * reps
}

enum class PrType {
    ESTIMATED_1RM,
    MAX_WEIGHT,
    MAX_VOLUME,
}

/** Blueprint §5: a personal record, with the set that produced it. */
data class PRRecord(
    val exerciseId: String,
    val exerciseName: String,
    val type: PrType,
    val value: Double,
    val date: LocalDate,
    val sourceSet: SetEntry? = null,
)

/** Blueprint §5: manual bodyweight log entry. */
data class BodyEntry(
    val date: LocalDate,
    val weightKg: Double,
)

/** Blueprint §5: read-only mirror of resting HR / HRV from Health Connect / passive monitoring. */
data class HeartTrend(
    val date: LocalDate,
    val restingHeartRateBpm: Int,
    val hrvMs: Double,
)
