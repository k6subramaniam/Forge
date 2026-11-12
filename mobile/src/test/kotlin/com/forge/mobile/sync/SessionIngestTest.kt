package com.forge.mobile.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.forge.core.model.SetEntry
import com.forge.core.model.WorkoutSession
import com.forge.core.sync.ForgeWire
import com.forge.mobile.data.ForgeRepository
import com.forge.mobile.data.db.ForgeDatabase
import com.forge.mobile.data.secure.HealthProfileStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
class SessionIngestTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private lateinit var database: ForgeDatabase
    private lateinit var repository: ForgeRepository
    private val mirrored = mutableListOf<WorkoutSession>()

    // Millisecond precision: what the watch records and what Room stores.
    private val start: Instant = Instant.parse("2026-11-16T07:00:00.123Z")
    private val session = WorkoutSession(
        id = "watch-s1", routineId = "routine-push", routineName = "Push",
        startTime = start, endTime = start.plusSeconds(3_000),
    )
    private val sets = (1..3).map { n ->
        SetEntry("watch-s1-set-$n", "watch-s1", "ex-bench-press", "Bench Press", n, 62.5, 10, true, start.plusSeconds(n * 180L))
    }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, ForgeDatabase::class.java).allowMainThreadQueries().build()
        repository = ForgeRepository(
            database,
            HealthProfileStore(File(context.noBackupFilesDir, "ingest_test.enc").also { it.delete() }) { key },
        )
    }

    @After
    fun tearDown() = database.close()

    private val pending = object : PendingMirrors {
        val ids = mutableSetOf<String>()
        override fun add(sessionId: String) { ids += sessionId }
        override fun remove(sessionId: String) { ids -= sessionId }
        override fun all(): Set<String> = ids.toSet()
    }

    private fun ingest(mirror: suspend (WorkoutSession) -> Boolean = { mirrored += it; true }) =
        SessionIngest(repository, { mirror(it) }, pending)

    @Test
    fun `a finished watch session is stored and mirrored`() = runTest {
        assertTrue(ingest().ingest(ForgeWire.encodeSession(session, sets)))

        val stored = repository.observeHistory().first().single()
        assertEquals(session, stored.session)
        assertEquals(sets, stored.sets)
        assertEquals(listOf(session), mirrored)
    }

    @Test
    fun `delivering the same session twice stores it once`() = runTest {
        val bytes = ForgeWire.encodeSession(session, sets)
        assertTrue(ingest().ingest(bytes))
        assertTrue(ingest().ingest(bytes))
        val history = repository.observeHistory().first()
        assertEquals(1, history.size)
        assertEquals(3, history.single().sets.size)
    }

    @Test
    fun `what can't be stored is kept for later, not dropped`() = runTest {
        val newer = ForgeWire.encodeSession(session, sets).toString(Charsets.UTF_8)
            .replaceFirst("\"v\":${ForgeWire.VERSION}", "\"v\":${ForgeWire.VERSION + 1}")
            .toByteArray()
        val unfinished = ForgeWire.encodeSession(session.copy(endTime = null), sets)
        val foreignSet = ForgeWire.encodeSession(session, sets + sets.first().copy(id = "x", sessionId = "other"))

        for (bytes in listOf(newer, "garbage".toByteArray(), unfinished, foreignSet)) {
            assertFalse(ingest().ingest(bytes))
        }
        assertTrue(repository.observeHistory().first().isEmpty())
        assertTrue(mirrored.isEmpty())
    }

    @Test
    fun `a Health Connect failure doesn't lose the stored session, and it's retried`() = runTest {
        val stored = ingest { throw IllegalStateException("Health Connect unavailable") }
            .ingest(ForgeWire.encodeSession(session, sets))
        assertTrue(stored)
        assertEquals(1, repository.observeHistory().first().size)
        assertEquals(setOf(session.id), pending.all())

        // The next sync writes it and clears the queue.
        ingest().retryPendingMirrors()
        assertEquals(listOf(session), mirrored)
        assertTrue(pending.all().isEmpty())
    }

    @Test
    fun `sessions stored before the permission are mirrored once it's granted`() = runTest {
        var permitted = false
        val writer = ingest { if (permitted) { mirrored += it; true } else false }
        assertTrue(writer.ingest(ForgeWire.encodeSession(session, sets)))
        assertTrue(mirrored.isEmpty())
        assertEquals(setOf(session.id), pending.all())

        writer.retryPendingMirrors() // still not permitted: stays queued
        assertEquals(setOf(session.id), pending.all())

        permitted = true
        writer.retryPendingMirrors()
        assertEquals(listOf(session), mirrored)
        assertTrue(pending.all().isEmpty())
    }

    @Test
    fun `a queued session that no longer exists leaves the queue`() = runTest {
        pending.add("deleted-session")
        ingest().retryPendingMirrors()
        assertTrue(pending.all().isEmpty())
        assertTrue(mirrored.isEmpty())
    }
}
