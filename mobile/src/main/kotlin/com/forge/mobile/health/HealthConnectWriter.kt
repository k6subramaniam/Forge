package com.forge.mobile.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import com.forge.core.model.WorkoutSession
import java.time.ZoneId

/**
 * Writes completed workouts to Health Connect so they appear in Samsung Health
 * alongside everything else (blueprint 3.6: the phone writes completed workouts out).
 *
 * Phase 1 status: the write path is implemented against the stable Health Connect
 * 1.1.0 API; what remains is the permission-request UX and the call site.
 *
 * TODO(Phase 1): request [REQUIRED_PERMISSIONS] via
 * PermissionController.createRequestPermissionResultContract() before first write,
 * and invoke [writeCompletedSession] when a session arrives from the watch
 * (Data Layer) or is finished on the phone.
 */
class HealthConnectWriter(private val context: Context) {

    companion object {
        val REQUIRED_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        )
    }

    /** True when Health Connect is present and usable on this device. */
    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    /**
     * Inserts one ExerciseSessionRecord for a finished session.
     * Sessions without an end time (still in progress) are skipped by contract —
     * Health Connect also requires end > start, which the require() enforces.
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
            metadata = Metadata.manualEntry(),
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            title = session.routineName ?: "Forge strength session",
            notes = session.notes,
        )
        client.insertRecords(listOf(record))
    }
}
