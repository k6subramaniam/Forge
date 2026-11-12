package com.forge.wear.sync

import android.util.AtomicFile
import android.util.Log
import com.forge.core.sync.ForgeWire
import com.forge.core.sync.SessionJournal
import java.io.File
import java.io.IOException

/**
 * The watch's own storage for workouts in flight.
 *
 * - The active session's [SessionJournal], rewritten after every command, so
 *   a session survives the watch killing the app mid-workout.
 * - An outbox of finished sessions not yet handed to the Data Layer. A
 *   session is written here before its journal is cleared, so it exists on
 *   disk at every moment until the phone has it.
 *
 * Writes go through [AtomicFile]: a crash mid-write leaves the previous copy.
 */
class SessionStore(dir: File) {

    private val journalFile = AtomicFile(File(dir, "active-session.json"))
    private val outboxDir = File(dir, "outbox")

    /** The session in progress, or null. An unreadable journal is set aside (kept for diagnosis), not deleted. */
    fun readJournal(): SessionJournal? {
        if (!journalFile.baseFile.exists()) return null
        return try {
            ForgeWire.decodeJournal(journalFile.readFully())
        } catch (e: Exception) {
            Log.e(TAG, "Unreadable session journal; set aside", e)
            journalFile.baseFile.renameTo(File(journalFile.baseFile.path + ".unreadable"))
            null
        }
    }

    /** Saves the journal, or clears it when [journal] is null. */
    fun writeJournal(journal: SessionJournal?) {
        if (journal == null) {
            journalFile.delete()
        } else {
            write(journalFile, ForgeWire.encodeJournal(journal))
        }
    }

    fun addToOutbox(sessionId: String, payload: ByteArray) {
        outboxDir.mkdirs()
        write(AtomicFile(File(outboxDir, "$sessionId.json")), payload)
    }

    /** Finished sessions waiting to be handed over, as (session id, payload). */
    fun outbox(): List<Pair<String, ByteArray>> =
        outboxDir.listFiles { file -> file.name.endsWith(".json") }.orEmpty()
            .sortedBy { it.lastModified() }
            .map { it.name.removeSuffix(".json") to AtomicFile(it).readFully() }

    fun removeFromOutbox(sessionId: String) {
        AtomicFile(File(outboxDir, "$sessionId.json")).delete()
    }

    private fun write(file: AtomicFile, bytes: ByteArray) {
        val out = file.startWrite()
        try {
            out.write(bytes)
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            throw e
        }
    }

    private companion object {
        const val TAG = "SessionStore"
    }
}
