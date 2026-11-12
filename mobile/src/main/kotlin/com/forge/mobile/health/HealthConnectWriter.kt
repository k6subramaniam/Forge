package com.forge.mobile.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import com.forge.core.model.WorkoutSession
import java.time.ZoneId

/** Somewhere finished workouts are mirrored to. Health Connect in the app; a fake in tests. */
fun interface WorkoutMirror {
    /** Mirrors a finished session. Does nothing when the destination isn't available or permitted. */
    suspend fun mirror(session: WorkoutSession)
}

/**
 * Writes completed workouts to Health Connect so they appear in Samsung Health
 * alongside everything else (blueprint 3.6: the phone writes completed workouts out).
 *
 * The user grants [REQUIRED_PERMISSIONS] from Home (PermissionController's
 * request contract). Until then, and on devices without Health Connect,
 * [mirror] does nothing. The workout is still stored in Forge.
 */
class HealthConnectWriter(private val context: Context) : WorkoutMirror {

    companion object {
        val REQUIRED_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        )

        /** Health Connect's id for a Forge session: stable, so a re-sent session updates its record. */
        fun clientRecordId(session: WorkoutSession): String = "forge-session-${session.id}"
    }

    /** True when Health Connect is present and usable on this device. */
    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasPermissions(): Boolean =
        isAvailable() &&
            HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
                .containsAll(REQUIRED_PERMISSIONS)

    override suspend fun mirror(session: WorkoutSession) {
        if (hasPermissions()) writeCompletedSession(session)
    }

    /**
     * Inserts one ExerciseSessionRecord for a finished session, or updates the
     * one written before: the record carries the session's [clientRecordId],
     * so delivering the same session twice can't create a duplicate workout.
     * Sessions without an end time (still in progress) are skipped by
     * contract. Health Connect also requires end > start, which the require()
     * enforces.
     */
    suspend fun writeCompletedSession(session: WorkoutSession) {
        val endTime = session.endTime ?: return
        require(endTime.isAfter(session.startTime)) { "Session end must be after its start" }

        val client = HealthConnectClient.getOrCreate(context)
        val zoneId = ZoneId.systemDefault()
        val record = ExerciseSessionRecord(
            startTime = session.startTime,
            startZoneOffset = zoneId.rules.getOffset(session.startTime),
            endTime = endTime,
            endZoneOffset = zoneId.rules.getOffset(endTime),
            metadata = Metadata.manualEntry(clientRecordId = clientRecordId(session), clientRecordVersion = 1),
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            title = session.routineName ?: "Forge strength session",
            notes = session.notes,
        )
        client.insertRecords(listOf(record))
    }
}
