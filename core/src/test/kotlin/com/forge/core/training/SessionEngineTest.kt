package com.forge.core.training

import com.forge.core.data.SampleData
import com.forge.core.model.HealthProfile
import com.forge.core.model.JointArea
import com.forge.core.model.PRRecord
import com.forge.core.model.PrType
import com.forge.core.model.Routine
import com.forge.core.model.RoutineExercise
import com.forge.core.model.WorkoutSession
import com.forge.core.onboarding.ExerciseExclusion
import com.forge.core.onboarding.FunctionalConstraints
import com.forge.core.onboarding.OnboardingGate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.LocalDate

class SessionEngineTest {

    private val start: Instant = Instant.parse("2026-11-10T15:00:00Z")
    private val date: LocalDate = LocalDate.of(2026, 11, 10)

    private fun at(minutes: Long): Instant = start.plusSeconds(minutes * 60)

    private fun log(state: LiveSessionState, weight: Double, reps: Int, minute: Long): LogSetResult =
        SessionEngine.logSet(state, weightKg = weight, reps = reps, timestamp = at(minute))

    /** Logs [count] identical sets against the current exercise, returning the final state. */
    private fun logSets(state: LiveSessionState, count: Int, weight: Double, reps: Int, fromMinute: Long = 1): LiveSessionState {
        var s = state
        repeat(count) { i -> s = log(s, weight, reps, fromMinute + i).state }
        return s
    }

    private fun constraintsFor(profile: HealthProfile): FunctionalConstraints =
        OnboardingGate.evaluate(profile).constraints

    // -- Starting ------------------------------------------------------------

    @Test
    fun `start from a routine prescribes every exercise in order`() {
        val state = SessionEngine.start(SampleData.pushA, sessionId = "s1", startTime = start)
        assertEquals("s1", state.session.id)
        assertEquals(SampleData.pushA.id, state.session.routineId)
        assertEquals("Push A", state.session.routineName)
        assertNull(state.session.endTime)
        assertEquals(
            listOf("Bench Press", "Overhead Press", "Tricep Pushdown"),
            state.exercises.map { it.exercise.name },
        )
        assertEquals(0, state.currentExerciseIndex)
        assertTrue(state.droppedExercises.isEmpty())
        assertFalse(state.allWorkComplete)
    }

    @Test
    fun `start freeform is empty until exercises are added`() {
        var state = SessionEngine.startFreeform(sessionId = "s2", startTime = start)
        assertNull(state.session.routineId)
        assertTrue(state.exercises.isEmpty())
        assertNull(state.currentExerciseIndex)

        state = SessionEngine.addExercise(
            state,
            RoutineExercise(SampleData.benchPress, targetSets = 2, repRange = 6..8, targetWeightKg = 60.0),
        )
        assertEquals(1, state.exercises.size)
        assertEquals(0, state.currentExerciseIndex)
        assertEquals("Bench Press", state.currentExercise?.exercise?.name)
    }

    // -- Logging and advancing -------------------------------------------------

    @Test
    fun `logging sets fills the current exercise and returns its rest prescription`() {
        val state = SessionEngine.start(SampleData.pushA, sessionId = "s1", startTime = start)

        val first = log(state, 60.0, 8, minute = 1)
        assertEquals("s1-set-1", first.loggedSet.id)
        assertEquals(1, first.loggedSet.setNumber)
        assertEquals("ex-bench-press", first.loggedSet.exerciseId)
        assertFalse(first.exerciseJustCompleted)
        val rest = first.rest
        assertNotNull(rest)
        assertEquals(120, rest!!.totalSeconds) // bench rest from Push A
        assertTrue(rest.isRunning)

        val afterFour = logSets(first.state, 3, 60.0, 8, fromMinute = 2)
        val bench = afterFour.exercises[0]
        assertEquals(4, bench.loggedSets.size)
        assertTrue(bench.isComplete)
        // Session moved on to the overhead press by itself.
        assertEquals(1, afterFour.currentExerciseIndex)
        assertEquals("Overhead Press", afterFour.currentExercise?.exercise?.name)
    }

    @Test
    fun `the final set of the session returns no rest and completes all work`() {
        var state = SessionEngine.start(SampleData.pushA, sessionId = "s1", startTime = start)
        state = logSets(state, 4, 60.0, 8) // bench
        state = logSets(state, 3, 40.0, 10) // overhead press
        val last = logSets(state, 2, 25.0, 12) // first two tricep sets
        val final = log(last, 25.0, 12, minute = 30)
        assertTrue(final.exerciseJustCompleted)
        assertTrue(final.allWorkComplete)
        assertNull(final.rest)
        assertNull(final.state.currentExerciseIndex)
        assertEquals(4020.0, final.state.totalVolumeKg, 0.0)
    }

    @Test
    fun `extra sets beyond the prescription can be logged after going back`() {
        var state = SessionEngine.start(SampleData.pushA, sessionId = "s1", startTime = start)
        state = logSets(state, 4, 60.0, 8)
        assertEquals(1, state.currentExerciseIndex)
        state = SessionEngine.goToExercise(state, 0)
        val extra = log(state, 62.5, 6, minute = 20)
        assertEquals(5, extra.loggedSet.setNumber)
        assertEquals(5, extra.state.exercises[0].loggedSets.size)
    }

    @Test
    fun `effort fields are recorded on the set`() {
        val state = SessionEngine.start(SampleData.pushA, sessionId = "s1", startTime = start)
        val result = SessionEngine.logSet(state, 60.0, 8, at(1), rpe = 8.5, rir = 2)
        assertEquals(8.5, result.loggedSet.rpe)
        assertEquals(2, result.loggedSet.rir)
        assertFalse(result.loggedSet.painReported)
    }

    @Test
    fun `logging validates its inputs and the session state`() {
        val state = SessionEngine.start(SampleData.pushA, sessionId = "s1", startTime = start)
        assertThrows<IllegalArgumentException> { SessionEngine.logSet(state, -1.0, 8, at(1)) }
        assertThrows<IllegalArgumentException> { SessionEngine.logSet(state, 60.0, 0, at(1)) }
        assertThrows<IllegalArgumentException> { SessionEngine.logSet(state, 60.0, 8, at(1), rpe = 11.0) }
        assertThrows<IllegalArgumentException> { SessionEngine.logSet(state, 60.0, 8, at(1), rir = -1) }

        val paused = SessionEngine.pause(state)
        assertThrows<IllegalArgumentException> { SessionEngine.logSet(paused, 60.0, 8, at(1)) }

        val empty = SessionEngine.startFreeform(sessionId = "s2", startTime = start)
        assertThrows<IllegalStateException> { SessionEngine.logSet(empty, 60.0, 8, at(1)) }

        val finishedState = LiveSessionState(
            session = WorkoutSession(id = "s3", startTime = start, endTime = at(60)),
        )
        assertThrows<IllegalStateException> { SessionEngine.logSet(finishedState, 60.0, 8, at(1)) }
    }

    // -- Navigation ------------------------------------------------------------

    @Test
    fun `skip marks the exercise and moves on, and going back un-skips it`() {
        var state = SessionEngine.start(SampleData.pushA, sessionId = "s1", startTime = start)
        state = SessionEngine.skipExercise(state)
        assertTrue(state.exercises[0].skipped)
        assertEquals(1, state.currentExerciseIndex)

        state = SessionEngine.goToExercise(state, 0)
        assertFalse(state.exercises[0].skipped)
        assertEquals(0, state.currentExerciseIndex)
    }

    @Test
    fun `going back to an earlier exercise keeps its logged sets`() {
        var state = SessionEngine.start(SampleData.pushA, sessionId = "s1", startTime = start)
        state = logSets(state, 2, 60.0, 8)
        state = SessionEngine.moveToNext(state)
        assertEquals(1, state.currentExerciseIndex)
        assertFalse(state.exercises[0].skipped)
        assertFalse(state.exercises[0].isComplete)

        state = SessionEngine.goToExercise(state, 0)
        assertEquals(2, state.exercises[0].loggedSets.size)
        val third = log(state, 60.0, 8, minute = 10)
        assertEquals(3, third.loggedSet.setNumber)
    }

    @Test
    fun `pause and resume toggle the flag`() {
        val state = SessionEngine.start(SampleData.pushA, sessionId = "s1", startTime = start)
        assertTrue(SessionEngine.pause(state).isPaused)
        assertFalse(SessionEngine.resume(SessionEngine.pause(state)).isPaused)
    }

    // -- Undo ------------------------------------------------------------------

    @Test
    fun `undo removes the last logged set and returns the pointer to its exercise`() {
        var state = SessionEngine.start(SampleData.pushA, sessionId = "s1", startTime = start)
        state = logSets(state, 4, 60.0, 8) // bench complete, pointer on overhead press
        state = logSets(state, 1, 40.0, 10) // one overhead set

        state = SessionEngine.undoLastSet(state)
        assertEquals(0, state.exercises[1].loggedSets.size)
        assertEquals(1, state.currentExerciseIndex)

        state = SessionEngine.undoLastSet(state) // now removes bench's fourth set
        assertEquals(3, state.exercises[0].loggedSets.size)
        assertFalse(state.exercises[0].isComplete)
        assertEquals(0, state.currentExerciseIndex)
    }

    @Test
    fun `undo with nothing logged is a no-op`() {
        val state = SessionEngine.start(SampleData.pushA, sessionId = "s1", startTime = start)
        assertEquals(state, SessionEngine.undoLastSet(state))
    }

    // -- Hard constraints (blueprint 3.0) ----------------------------------------

    @Test
    fun `an excluded exercise is substituted and never prescribed`() {
        val constraints = constraintsFor(
            HealthProfile(jointLimitations = setOf(JointArea.SHOULDER), onboardingComplete = true),
        )
        val state = SessionEngine.start(SampleData.pushA, sessionId = "s1", startTime = start, constraints = constraints)

        assertEquals(3, state.exercises.size)
        val overhead = state.exercises[1]
        assertEquals("Landmine press", overhead.exercise.name) // first shoulder substitution
        assertEquals(SampleData.overheadPress, overhead.substitutedFor)
        assertNotNull(overhead.adjustmentReason)
        assertTrue(overhead.adjustmentReason!!.contains("Overhead Press"))
        // The excluded exercise itself is nowhere in the plan.
        assertTrue(state.exercises.none { it.exercise.id == SampleData.overheadPress.id })
        assertTrue(state.droppedExercises.isEmpty())
        // Untouched exercises keep their prescriptions.
        assertEquals(SampleData.benchPress, state.exercises[0].exercise)
        assertEquals(40.0, overhead.prescription.targetWeightKg, 0.0)
    }

    @Test
    fun `a knee limitation substitutes the squat with the first safe variant`() {
        val constraints = constraintsFor(
            HealthProfile(jointLimitations = setOf(JointArea.KNEE), onboardingComplete = true),
        )
        val state = SessionEngine.start(SampleData.legsA, sessionId = "s1", startTime = start, constraints = constraints)
        val squatSlot = state.exercises[0]
        assertEquals("Box squat to comfortable depth", squatSlot.exercise.name)
        assertEquals(SampleData.squat, squatSlot.substitutedFor)
        assertTrue(squatSlot.exercise.id.startsWith("substitute-"))
        // Neighbours are unaffected.
        assertEquals(SampleData.deadlift, state.exercises[1].exercise)
        assertEquals(SampleData.legPress, state.exercises[2].exercise)
    }

    @Test
    fun `a substitution that exists in the library resolves to the real exercise`() {
        val constraints = FunctionalConstraints(
            exerciseExclusions = listOf(
                ExerciseExclusion(
                    area = JointArea.KNEE,
                    excludedMovements = listOf("Squat"),
                    substitutions = listOf("Leg press"),
                    reason = "Test knee limitation.",
                ),
            ),
            heartRateCapPercentOfMax = null,
            intensityMayOnlyScaleDown = false,
            promptConstraints = emptyList(),
        )
        val state = SessionEngine.start(SampleData.legsA, sessionId = "s1", startTime = start, constraints = constraints)
        assertEquals(SampleData.legPress, state.exercises[0].exercise)
        assertEquals(SampleData.squat, state.exercises[0].substitutedFor)
    }

    @Test
    fun `an excluded exercise with no safe substitution is dropped and the reason recorded`() {
        val constraints = FunctionalConstraints(
            exerciseExclusions = listOf(
                ExerciseExclusion(
                    area = JointArea.KNEE,
                    excludedMovements = listOf("Squat"),
                    substitutions = listOf("Lunge"), // itself excluded below
                    reason = "Test knee limitation.",
                ),
                ExerciseExclusion(
                    area = JointArea.ANKLE,
                    excludedMovements = listOf("Lunge"),
                    substitutions = emptyList(),
                    reason = "Test ankle limitation.",
                ),
            ),
            heartRateCapPercentOfMax = null,
            intensityMayOnlyScaleDown = false,
            promptConstraints = emptyList(),
        )
        val state = SessionEngine.start(SampleData.legsA, sessionId = "s1", startTime = start, constraints = constraints)
        assertEquals(
            listOf("Deadlift", "Leg Press", "Calf Raise"),
            state.exercises.map { it.exercise.name },
        )
        assertEquals(1, state.droppedExercises.size)
        assertEquals(SampleData.squat, state.droppedExercises.single().exercise)
        assertTrue(state.droppedExercises.single().reason.contains("Squat"))
        assertEquals(0, state.currentExerciseIndex)
    }

    @Test
    fun `freeform additions go through the same constraints`() {
        val constraints = constraintsFor(
            HealthProfile(jointLimitations = setOf(JointArea.KNEE), onboardingComplete = true),
        )
        var state = SessionEngine.startFreeform(sessionId = "s2", startTime = start)
        state = SessionEngine.addExercise(
            state,
            RoutineExercise(SampleData.squat, targetSets = 3, repRange = 5..6, targetWeightKg = 80.0),
            constraints = constraints,
        )
        assertEquals("Box squat to comfortable depth", state.exercises.single().exercise.name)
        assertEquals(SampleData.squat, state.exercises.single().substitutedFor)
    }

    // -- Pain (blueprint 3.0) ----------------------------------------------------

    @Test
    fun `a pain flag records the set, swaps the exercise out, and moves the session on`() {
        var state = SessionEngine.start(SampleData.pushA, sessionId = "s1", startTime = start)
        state = logSets(state, 1, 60.0, 8)
        val pain = SessionEngine.logSet(state, 60.0, 6, at(5), painReported = true)

        assertTrue(pain.loggedSet.painReported)
        val bench = pain.state.exercises[0]
        assertEquals(2, bench.loggedSets.size) // both sets stay in the record
        assertTrue(bench.painReported)
        assertTrue(pain.state.isRemovedForPain(0))
        assertEquals(1, pain.state.currentExerciseIndex) // moved on to overhead press
        assertThrows<IllegalArgumentException> { SessionEngine.goToExercise(pain.state, 0) }

        val finished = SessionEngine.finish(pain.state, endTime = at(45), date = date)
        assertEquals(listOf("Bench Press"), finished.summary.painExercises)
        assertEquals(2, finished.sets.count { it.exerciseId == "ex-bench-press" })
    }

    @Test
    fun `undoing the pain set restores the exercise`() {
        var state = SessionEngine.start(SampleData.pushA, sessionId = "s1", startTime = start)
        state = SessionEngine.logSet(state, 60.0, 6, at(5), painReported = true).state
        assertTrue(state.isRemovedForPain(0))

        state = SessionEngine.undoLastSet(state)
        assertFalse(state.isRemovedForPain(0))
        assertEquals(0, state.currentExerciseIndex)
    }

    /** Bench twice (two sets each) around an overhead press block. */
    private val benchTwice = Routine(
        id = "routine-bench-twice",
        name = "Bench twice",
        exercises = listOf(
            RoutineExercise(SampleData.benchPress, targetSets = 2, repRange = 6..8, targetWeightKg = 60.0),
            RoutineExercise(SampleData.overheadPress, targetSets = 2, repRange = 8..10, targetWeightKg = 40.0),
            RoutineExercise(SampleData.benchPress, targetSets = 2, repRange = 6..8, targetWeightKg = 60.0),
        ),
    )

    @Test
    fun `pain in one entry removes every entry of that exercise`() {
        // Codex P1 (#1): the second bench block must not be prescribed again.
        var state = SessionEngine.start(benchTwice, sessionId = "s1", startTime = start)
        state = SessionEngine.logSet(state, 60.0, 6, at(1), painReported = true).state

        assertTrue(state.isRemovedForPain(0))
        assertTrue(state.isRemovedForPain(2))
        assertEquals(1, state.currentExerciseIndex) // overhead press, not the second bench
        assertThrows<IllegalArgumentException> { SessionEngine.goToExercise(state, 2) }

        // Finishing overhead press leaves nothing: both bench entries are out.
        val done = SessionEngine.logSet(logSets(state, 1, 40.0, 10, fromMinute = 2), 40.0, 10, at(4))
        assertTrue(done.allWorkComplete)
        assertNull(done.state.currentExerciseIndex)
        assertEquals(listOf("Bench Press"), SessionEngine.finish(done.state, at(30), date).summary.painExercises)
    }

    @Test
    fun `undoing the pain set restores every entry of the exercise`() {
        var state = SessionEngine.start(benchTwice, sessionId = "s1", startTime = start)
        state = SessionEngine.logSet(state, 60.0, 6, at(1), painReported = true).state
        state = SessionEngine.undoLastSet(state)
        assertFalse(state.isRemovedForPain(0))
        assertFalse(state.isRemovedForPain(2))
        assertTrue(state.isAvailable(2))
    }

    @Test
    fun `an exercise added after a pain report stays out until the pain set is undone`() {
        var state = SessionEngine.start(SampleData.pushA, sessionId = "s1", startTime = start)
        state = SessionEngine.logSet(state, 60.0, 6, at(1), painReported = true).state // bench out
        state = SessionEngine.skipExercise(state) // overhead press
        state = SessionEngine.skipExercise(state) // tricep pushdown: no work left
        assertNull(state.currentExerciseIndex)

        state = SessionEngine.addExercise(
            state,
            RoutineExercise(SampleData.benchPress, targetSets = 3, repRange = 6..8, targetWeightKg = 60.0),
        )
        val added = state.exercises.lastIndex
        assertTrue(state.isRemovedForPain(added))
        assertNull(state.currentExerciseIndex) // never pointed at a pain-removed exercise

        state = SessionEngine.undoLastSet(state)
        assertTrue(state.isAvailable(added))
    }

    @Test
    fun `undo onto a skipped exercise un-skips it so the corrected set can be logged`() {
        // Codex P2 (#1): log, skip, undo used to land on a skipped, unloggable exercise.
        var state = SessionEngine.start(SampleData.pushA, sessionId = "s1", startTime = start)
        state = log(state, 60.0, 8, 1).state
        state = SessionEngine.skipExercise(state)
        assertEquals(1, state.currentExerciseIndex)

        state = SessionEngine.undoLastSet(state)
        assertEquals(0, state.currentExerciseIndex)
        assertFalse(state.exercises[0].skipped)
        val relogged = log(state, 62.5, 8, 2)
        assertEquals(62.5, relogged.loggedSet.weightKg)
    }

    // -- Finishing ---------------------------------------------------------------

    @Test
    fun `finish produces a completed session and a full summary`() {
        var state = SessionEngine.start(SampleData.pushA, sessionId = "s1", startTime = start)
        state = logSets(state, 4, 60.0, 8) // bench: 4 x 480 = 1920
        state = logSets(state, 3, 40.0, 10) // overhead: 3 x 400 = 1200
        state = logSets(state, 3, 25.0, 12) // tricep: 3 x 300 = 900

        val finished = SessionEngine.finish(
            state,
            endTime = at(60),
            date = date,
            avgHeartRateBpm = 118,
            maxHeartRateBpm = 152,
        )
        assertTrue(finished.session.isCompleted)
        assertEquals(at(60), finished.session.endTime)
        assertEquals(SampleData.pushA.id, finished.session.routineId)
        assertEquals(118, finished.session.avgHeartRateBpm)
        assertEquals(10, finished.sets.size)

        val summary = finished.summary
        assertEquals(3600, summary.durationSeconds)
        assertEquals(10, summary.totalSets)
        assertEquals(4020.0, summary.totalVolumeKg, 0.0)
        assertEquals(3, summary.exercises.size)
        val bench = summary.exercises.first { it.exerciseId == "ex-bench-press" }
        assertEquals(1920.0, bench.totalVolumeKg, 0.0)
        val bestSet = bench.bestSet
        assertNotNull(bestSet)
        assertEquals(60.0, bestSet!!.weightKg, 0.0)
        assertEquals(8, bestSet.reps)
        assertTrue(summary.painExercises.isEmpty())
    }

    @Test
    fun `finish detects PRs against provided history`() {
        val history = listOf(
            PRRecord("ex-bench-press", "Bench Press", PrType.MAX_WEIGHT, 55.0, date.minusWeeks(1)),
            PRRecord("ex-bench-press", "Bench Press", PrType.MAX_VOLUME, 400.0, date.minusWeeks(1)),
            PRRecord("ex-bench-press", "Bench Press", PrType.ESTIMATED_1RM, 70.0, date.minusWeeks(1)),
        )
        var state = SessionEngine.start(SampleData.pushA, sessionId = "s1", startTime = start)
        state = logSets(state, 1, 60.0, 8) // 60 kg, 480 volume, 76.0 est. 1RM — beats all three
        val finished = SessionEngine.finish(state, endTime = at(30), date = date, existingPrs = history)

        val benchPrs = finished.summary.newPrs.filter { it.exerciseId == "ex-bench-press" }
        assertEquals(
            setOf(PrType.MAX_WEIGHT, PrType.MAX_VOLUME, PrType.ESTIMATED_1RM),
            benchPrs.map { it.type }.toSet(),
        )
        assertEquals(60.0, benchPrs.first { it.type == PrType.MAX_WEIGHT }.value, 0.0)
        assertEquals(480.0, benchPrs.first { it.type == PrType.MAX_VOLUME }.value, 0.0)
        assertEquals(76.0, benchPrs.first { it.type == PrType.ESTIMATED_1RM }.value, 0.001)
        assertEquals(date, benchPrs.first().date)
    }

    @Test
    fun `no PRs when history already beats the session`() {
        val history = listOf(
            PRRecord("ex-bench-press", "Bench Press", PrType.MAX_WEIGHT, 100.0, date.minusWeeks(1)),
            PRRecord("ex-bench-press", "Bench Press", PrType.MAX_VOLUME, 1000.0, date.minusWeeks(1)),
            PRRecord("ex-bench-press", "Bench Press", PrType.ESTIMATED_1RM, 150.0, date.minusWeeks(1)),
        )
        var state = SessionEngine.start(SampleData.pushA, sessionId = "s1", startTime = start)
        state = logSets(state, 1, 60.0, 8)
        val finished = SessionEngine.finish(state, endTime = at(30), date = date, existingPrs = history)
        assertTrue(finished.summary.newPrs.none { it.exerciseId == "ex-bench-press" })
    }

    @Test
    fun `finish rejects an end time before the start`() {
        val state = SessionEngine.start(SampleData.pushA, sessionId = "s1", startTime = start)
        assertThrows<IllegalArgumentException> {
            SessionEngine.finish(state, endTime = start.minusSeconds(60), date = date)
        }
    }

    @Test
    fun `a freeform session finishes with no routine attached`() {
        var state = SessionEngine.startFreeform(sessionId = "s2", startTime = start)
        state = SessionEngine.addExercise(
            state,
            RoutineExercise(SampleData.benchPress, targetSets = 2, repRange = 6..8, targetWeightKg = 60.0),
        )
        state = logSets(state, 2, 60.0, 8)
        state = SessionEngine.addExercise(
            state,
            RoutineExercise(SampleData.squat, targetSets = 1, repRange = 5..6, targetWeightKg = 80.0),
        )
        state = logSets(state, 1, 80.0, 5)

        val finished = SessionEngine.finish(state, endTime = at(40), date = date)
        assertNull(finished.session.routineId)
        assertEquals(3, finished.summary.totalSets)
        assertEquals(2, finished.summary.exercises.size)
        assertEquals(960.0 + 400.0, finished.summary.totalVolumeKg, 0.0)
    }

    @Test
    fun `an untouched routine can still be finished, with an empty summary`() {
        val routine = Routine("r-empty", "Nothing", emptyList())
        val state = SessionEngine.start(routine, sessionId = "s9", startTime = start)
        assertNull(state.currentExerciseIndex)
        val finished = SessionEngine.finish(state, endTime = at(5), date = date)
        assertEquals(0, finished.summary.totalSets)
        assertEquals(0.0, finished.summary.totalVolumeKg, 0.0)
        assertTrue(finished.summary.newPrs.isEmpty())
    }

    // -- Regression: navigation wrap-around (Codex review) -----------------------

    @Test
    fun `finishing the last exercise wraps back to an unfinished earlier one`() {
        var state = SessionEngine.start(SampleData.pushA, sessionId = "s1", startTime = start)
        // Advance early twice, leaving bench and overhead press untouched.
        state = SessionEngine.moveToNext(state)
        state = SessionEngine.moveToNext(state)
        assertEquals(2, state.currentExerciseIndex)
        assertEquals("Tricep Pushdown", state.currentExercise?.exercise?.name)

        // Completing the last exercise must land back on the bench press, not
        // strand the session with no current exercise.
        val twoSets = logSets(state, 2, 25.0, 12)
        val completing = log(twoSets, 25.0, 12, minute = 20)
        assertTrue(completing.exerciseJustCompleted)
        assertFalse(completing.allWorkComplete)
        assertNotNull(completing.rest)
        assertEquals(0, completing.state.currentExerciseIndex)
        assertEquals("Bench Press", completing.state.currentExercise?.exercise?.name)

        // And logging works again from there.
        val back = log(completing.state, 60.0, 8, minute = 21)
        assertEquals(1, back.state.exercises[0].loggedSets.size)
        assertEquals(0, back.state.currentExerciseIndex)
    }

    @Test
    fun `skipping the last exercise wraps back to an unfinished earlier one`() {
        var state = SessionEngine.start(SampleData.pushA, sessionId = "s1", startTime = start)
        state = SessionEngine.moveToNext(state)
        state = SessionEngine.moveToNext(state)
        state = SessionEngine.skipExercise(state)
        assertTrue(state.exercises[2].skipped)
        assertEquals(0, state.currentExerciseIndex)
        assertEquals("Bench Press", state.currentExercise?.exercise?.name)
    }

    // -- Regression: PR detection across duplicate entries (Codex review) --------

    /** The same exercise prescribed twice in one routine (a repeated block). */
    private fun benchTwiceRoutine(): Routine = Routine(
        id = "r-bench-twice",
        name = "Bench Twice",
        exercises = listOf(
            RoutineExercise(SampleData.benchPress, targetSets = 1, repRange = 5..5, targetWeightKg = 100.0),
            RoutineExercise(SampleData.benchPress, targetSets = 1, repRange = 5..5, targetWeightKg = 80.0),
        ),
    )

    @Test
    fun `a weaker second entry of the same exercise does not produce false PRs`() {
        var state = SessionEngine.start(benchTwiceRoutine(), sessionId = "s1", startTime = start)
        state = logSets(state, 1, 100.0, 5) // first entry: 100 kg, no history to beat
        assertEquals(1, state.currentExerciseIndex) // advanced to the repeated entry
        state = logSets(state, 1, 80.0, 5) // second entry: weaker in every dimension

        val finished = SessionEngine.finish(state, endTime = at(30), date = date)
        val benchPrs = finished.summary.newPrs.filter { it.exerciseId == "ex-bench-press" }
        // Only the 100 kg set earns records — one per PR type. The 80 kg set,
        // checked against the session's own 100 kg rather than history alone,
        // earns nothing.
        assertEquals(3, benchPrs.size)
        assertEquals(100.0, benchPrs.first { it.type == PrType.MAX_WEIGHT }.value, 0.0)
        assertTrue(benchPrs.none { it.sourceSet?.weightKg == 80.0 })
    }

    @Test
    fun `a stronger second entry of the same exercise is reported once, in order`() {
        var state = SessionEngine.start(benchTwiceRoutine(), sessionId = "s1", startTime = start)
        state = logSets(state, 1, 80.0, 5) // first entry sets the marks
        state = logSets(state, 1, 100.0, 5) // second entry breaks them

        val finished = SessionEngine.finish(state, endTime = at(30), date = date)
        val benchPrs = finished.summary.newPrs.filter { it.exerciseId == "ex-bench-press" }
        // Each improvement is recorded exactly once, in logging order.
        assertEquals(
            listOf(80.0, 100.0),
            benchPrs.filter { it.type == PrType.MAX_WEIGHT }.map { it.value },
        )
        assertEquals(1, benchPrs.count { it.type == PrType.MAX_WEIGHT && it.value == 100.0 })
        assertEquals(6, benchPrs.size) // three PR types, each improved twice
    }
}
