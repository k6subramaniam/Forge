package com.forge.mobile.sync

import android.util.Log
import com.forge.core.model.WorkoutSession
import com.forge.core.sync.ForgeWire
import com.forge.core.sync.WireFormatException
import com.forge.mobile.data.ForgeRepository
import com.forge.mobile.health.WorkoutMirror

/**
 * Stores a session the watch finished (blueprint 3.6: the phone is the source
 * of truth) and mirrors it to Health Connect.
 *
 * A session Health Connect couldn't take (not permitted yet, or the write
 * failed) is queued in [PendingMirrors] and retried by [retryPendingMirrors].
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
    private val pending: PendingMirrors,
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
        // Health Connect is a mirror: a failure here must not lose the stored
        // session, and the session is queued to be mirrored later.
        mirrorOrQueue(session.id) { session }
        return true
    }

    /**
     * Mirrors every stored session still waiting for Health Connect (it wasn't
     * permitted yet, or the write failed). Runs on each sync and after the
     * permission is granted.
     */
    suspend fun retryPendingMirrors() {
        for (id in pending.all()) {
            mirrorOrQueue(id) { repository.session(id) }
        }
    }

    private suspend fun mirrorOrQueue(sessionId: String, load: suspend () -> WorkoutSession?) {
        try {
            val session = load()
            when {
                session == null -> pending.remove(sessionId) // deleted since; nothing to mirror
                mirror.mirror(session) -> pending.remove(sessionId)
                else -> pending.add(sessionId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Writing session $sessionId to Health Connect failed; will retry", e)
            pending.add(sessionId)
        }
    }

    private companion object {
        const val TAG = "SessionIngest"
    }
}
