package com.forge.mobile.sync

import android.util.Log
import com.forge.core.sync.ForgeWire
import com.forge.core.sync.WireFormatException
import com.forge.mobile.data.ForgeRepository
import com.forge.mobile.health.WorkoutMirror

/**
 * Stores a session the watch finished (blueprint 3.6: the phone is the source
 * of truth) and mirrors it to Health Connect.
 *
 * The Data Layer item is the only copy once the watch has handed it over, so
 * the caller deletes it only when [ingest] returns true: the session is in the
 * database. Anything else stays where it is for the next sync. That covers a
 * payload from a newer watch app, an unreadable one, and a failed database
 * write. Storing is idempotent (the session id is the key), so a repeat
 * delivery is harmless.
 */
class SessionIngest(
    private val repository: ForgeRepository,
    private val mirror: WorkoutMirror,
) {

    suspend fun ingest(bytes: ByteArray): Boolean {
        val completed = try {
            ForgeWire.decodeSession(bytes)
        } catch (e: WireFormatException) {
            val why = if (e.fromNewerVersion) "update the phone app to read it" else "unreadable"
            Log.w(TAG, "Kept a watch session for later: $why", e)
            return false
        }
        val session = completed.session
        if (!session.isCompleted || completed.sets.any { it.sessionId != session.id }) {
            Log.w(TAG, "Kept watch session ${session.id} for later: unfinished or inconsistent")
            return false
        }
        try {
            repository.addSession(session, completed.sets)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't store watch session ${session.id}; will retry", e)
            return false
        }
        // Health Connect is a mirror: a failure here must not lose the stored session.
        try {
            mirror.mirror(session)
        } catch (e: Exception) {
            Log.w(TAG, "Stored session ${session.id}, but writing it to Health Connect failed", e)
        }
        return true
    }

    private companion object {
        const val TAG = "SessionIngest"
    }
}
