package com.forge.core.sync

import com.forge.core.data.ExerciseLibrary
import com.forge.core.model.RoutineExercise
import com.forge.core.model.SetEntry
import com.forge.core.training.SessionEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.LocalDate

class SessionJournalTest {

    private val start: Instant = Instant.parse("2026-11-16T07:00:00Z")
    private fun at(minutes: Long) = start.plusSeconds(minutes * 60)

    private val plan = WorkoutPlan(
        date = LocalDate.of(2026, 11, 16),
        routineId = "routine-push",
        routineName = "Push",
        exercises = listOf(
            PlannedExercise(
                RoutineExercise(ExerciseLibrary.benchPress.copy(tags = null), 2, 8..12, 62.5),
                weightReason = "progressed",
                lastTopSet = null,
                substitutedFor = null,
                adjustmentReason = null,
            ),
            PlannedExercise(
                RoutineExercise(ExerciseLibrary.tricepPushdown.copy(tags = null), 2, 10..15, 25.0),
                weightReason = "no history",
                lastTopSet = null,
                substitutedFor = null,
                adjustmentReason = null,
            ),
        ),
        droppedExercises = emptyList(),
    )

    private val lastCurl = SetEntry(
        "old-1", "old", ExerciseLibrary.bicepCurl.id, "Bicep Curl", 1, 12.5, 10, true, Instant.parse("2026-11-09T07:30:00Z"),
    )
    private val menu = listOf(
        MenuExercise(ExerciseLibrary.bicepCurl.copy(tags = null), lastCurl),
        MenuExercise(ExerciseLibrary.squat.copy(tags = null), null),
    )

    private fun journal(withPlan: Boolean = true) =
        SessionJournal("s1", start, if (withPlan) plan else null, menu, existingPrs = emptyList())

    /** Records every command live, as the watch does, returning the final journal and state. */
    private fun runLive(journal: SessionJournal, commands: List<SessionCommand>) =
        commands.fold(journal to journal.replay()) { (j, state), command ->
            val (next, step) = j.record(state, command)
            next to step.state
        }

    private val workout = listOf(
        SessionCommand.LogSet(62.5, 10, at(5)),
        SessionCommand.LogSet(62.5, 8, at(8)),
        SessionCommand.LogSet(25.0, 12, at(12), painReported = true),
        SessionCommand.UndoLastSet,
        SessionCommand.LogSet(25.0, 12, at(14)),
        SessionCommand.AddExercise(ExerciseLibrary.bicepCurl.id),
        SessionCommand.SkipExercise,
    )

    @Test
    fun `replay rebuilds exactly the session the commands built live`() {
        val (journal, live) = runLive(journal(), workout)
        assertEquals(workout, journal.commands)
        assertEquals(live, journal.replay())
    }

    @Test
    fun `replay survives the trip to disk`() {
        val (journal, live) = runLive(journal(), workout)
        val restored = ForgeWire.decodeJournal(ForgeWire.encodeJournal(journal))
        assertEquals(journal, restored)
        assertEquals(live, restored.replay())
    }

    @Test
    fun `a plan session starts on its first exercise and logged sets get a rest`() {
        val j = journal()
        val (_, step) = j.record(j.replay(), SessionCommand.LogSet(62.5, 10, at(5)))
        assertEquals(ExerciseLibrary.benchPress.id, step.state.currentExercise?.exercise?.id)
        assertNotNull(step.rest)
        val (_, undo) = j.record(step.state, SessionCommand.UndoLastSet)
        assertNull(undo.rest)
    }

    @Test
    fun `freeform adds from the menu, starting at last time's weight`() {
        val j = journal(withPlan = false)
        val empty = j.replay()
        assertNull(empty.session.routineId)
        assertTrue(empty.exercises.isEmpty())

        val (_, step) = j.record(empty, SessionCommand.AddExercise(ExerciseLibrary.bicepCurl.id))
        val added = step.state.exercises.single()
        assertEquals(ExerciseLibrary.bicepCurl.id, added.exercise.id)
        assertEquals(12.5, added.prescription.targetWeightKg, 1e-9)
        assertEquals(MenuExercise.FREEFORM_SETS, added.prescription.targetSets)
        assertEquals(0, step.state.currentExerciseIndex)
    }

    @Test
    fun `a command the engine refuses is not recorded`() {
        val j = journal()
        val state = j.replay()
        assertThrows<IllegalArgumentException> { j.record(state, SessionCommand.AddExercise("not-on-menu")) }
        assertThrows<IllegalArgumentException> { j.record(state, SessionCommand.LogSet(62.5, 0, at(1))) }
        assertTrue(j.commands.isEmpty())
    }

    @Test
    fun `a finished journal session matches the engine run directly`() {
        val (journal, live) = runLive(journal(), workout)
        val direct = SessionEngine.finish(live, at(40), plan.date)
        val replayed = SessionEngine.finish(journal.replay(), at(40), plan.date)
        assertEquals(direct, replayed)
        // Bench (2) + pushdown (1 after the undo) completed sets; the pain set was undone.
        assertEquals(3, replayed.summary.totalSets)
        assertTrue(replayed.summary.painExercises.isEmpty())
    }
}
