package com.forge.mobile.sync

import android.content.Context

/** Ids of stored sessions not yet mirrored to Health Connect. */
interface PendingMirrors {
    fun add(sessionId: String)
    fun remove(sessionId: String)
    fun all(): Set<String>
}

/**
 * [PendingMirrors] kept in SharedPreferences: a set of session ids, nothing
 * else. It survives restarts, so a workout that couldn't be mirrored
 * (Health Connect not yet permitted, or a write that failed) is retried on
 * the next sync.
 */
class SharedPreferencesPendingMirrors(context: Context) : PendingMirrors {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    override fun add(sessionId: String) = update { it + sessionId }

    @Synchronized
    override fun remove(sessionId: String) = update { it - sessionId }

    @Synchronized
    override fun all(): Set<String> = prefs.getStringSet(KEY, emptySet()).orEmpty().toSet()

    private fun update(change: (Set<String>) -> Set<String>) {
        // A copy: the set SharedPreferences returns must not be modified.
        prefs.edit().putStringSet(KEY, change(all())).commit()
    }

    private companion object {
        const val FILE = "health_connect_pending"
        const val KEY = "session_ids"
    }
}
