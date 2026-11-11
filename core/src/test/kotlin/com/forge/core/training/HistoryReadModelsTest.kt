package com.forge.core.training

import com.forge.core.data.ExerciseLibrary
import com.forge.core.testing.TestRoutines
import com.forge.core.model.Exercise
import com.forge.core.model.HealthProfile
import com.forge.core.model.JointArea
import com.forge.core.model.PRRecord
import com.forge.core.model.PrType
import com.forge.core.model.Routine
import com.forge.core.model.RoutineExercise
import com.forge.core.model.SetEntry
import com.forge.core.model.WorkoutSession
import com.forge.core.onboarding.ExerciseExclusion
import com.forge.core.onboarding.FunctionalConstraints
import com.forge.core.onboarding.OnboardingGate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class HistoryReadModelsTest {

    private val zone: ZoneId = ZoneOffset.UTC

    private val bench = TestRoutines.benchPress
    private val overhead = TestRoutines.overheadPress
    private val squat = TestRoutines.squat

    /**
     * Builds a completed workout: a session plus its sets, three minutes apart
     * in the order given. Each exercise maps to its (weight, reps) sets.
     */
    private fun workout(
        id: String,
        startIso: String,
        vararg exerciseSets: Pair<Exercise, List<Pair<Double, Int>>>,
        durationMinutes: Long = 60,
    ): CompletedSession {
        val start = Instant.parse(startIso)
        val sets = mutableListOf<SetEntry>()
        var tick = 0L
        for ((exercise, performed) in exerciseSets) {
            performed.forEachIndexed { index, (weight, reps) ->
                tick++
                sets += SetEntry(
                    id = "$id-set-${sets.size + 1}",
                    sessionId = id,
                    exerciseId = exercise.id,
                    exerciseName = exercise.name,
                    setNumber = index + 1,
                    weightKg = weight,
                    reps = reps,
                    timestamp = start.plusSeconds(tick * 180),
                )
            }
        }
        return CompletedSession(
            session = WorkoutSession(
                id = id,
                startTime = start,
                endTime = start.plusSeconds(durationMinutes * 60),
            ),
            sets = sets,
        )
    }

    private fun constraintsFor(profile: HealthProfile): FunctionalConstraints =
        OnboardingGate.evaluate(profile).constraints

    // -- Ghost targets ---------------------------------------------------------

    @Test
    fun `ghost target comes from the most recent session, not the best ever`() {
        val older = workout(
            "s-old", "2026-11-03T15:00:00Z",
            bench to listOf(70.0 to 5, 70.0 to 5),
        )
        val newer = workout(
            "s-new", "2026-11-10T15:00:00Z",
            bench to listOf(60.0 to 8, 60.0 to 8, 60.0 to 6),
        )

        // Passed newest-first on purpose: the query sorts, not the caller.
        val ghost = HistoryReadModels.ghostTarget(listOf(newer, older), bench.id, zone)

        assertNotNull(ghost)
        assertEquals("s-new", ghost!!.sessionId)
        assertEquals(LocalDate.of(2026, 11, 10), ghost.date)
        assertEquals(60.0, ghost.topSet.weightKg, 1e-9)
        assertEquals(8, ghost.topSet.reps)
        assertEquals(listOf(60.0, 60.0, 60.0), ghost.sets.map { it.weightKg })
    }

    @Test
    fun `ghost target is null for a never performed exercise`() {
        val history = listOf(
            workout("s1", "2026-11-10T15:00:00Z", bench to listOf(60.0 to 8)),
        )
        assertNull(HistoryReadModels.ghostTarget(history, TestRoutines.calfRaise.id, zone))
        assertNull(HistoryReadModels.ghostTarget(history, overhead.id, zone))
    }

    @Test
    fun `ghost target skips a session whose sets for the exercise were all incomplete`() {
        val start = Instant.parse("2026-11-12T15:00:00Z")
        val allIncomplete = CompletedSession(
            session = WorkoutSession(id = "s-bad", startTime = start, endTime = start.plusSeconds(3600)),
            sets = listOf(
                SetEntry(
                    id = "s-bad-set-1", sessionId = "s-bad", exerciseId = bench.id,
                    exerciseName = bench.name, setNumber = 1, weightKg = 80.0, reps = 5,
                    completed = false, timestamp = start.plusSeconds(180),
                ),
            ),
        )
        val good = workout("s-good", "2026-11-10T15:00:00Z", bench to listOf(60.0 to 8))

        val ghost = HistoryReadModels.ghostTarget(listOf(allIncomplete, good), bench.id, zone)

        assertEquals("s-good", ghost!!.sessionId)
    }

    // -- Next-session prescription ----------------------------------------------

    @Test
    fun `prescription progresses the weight actually lifted by the rule increment`() {
        // Push A bench: 4 sets, 6..8 — last time all four hit the top (8) at 60 kg.
        val history = listOf(
            workout("s1", "2026-11-10T15:00:00Z", bench to listOf(60.0 to 8, 60.0 to 8, 60.0 to 8, 60.0 to 8)),
        )

        val plan = HistoryReadModels.nextSessionPrescription(TestRoutines.pushA, history, zone)

        val benchTarget = plan.exercises[0]
        assertEquals(bench.id, benchTarget.exercise.id)
        assertEquals(62.5, benchTarget.suggestedWeightKg, 1e-9)
        assertNotNull(benchTarget.overload)
        assertEquals(2.5, benchTarget.overload!!.incrementKg, 1e-9)
        assertNotNull(benchTarget.ghost)

        // No overhead history in the same plan: prescribed-target fallback.
        val overheadTarget = plan.exercises[1]
        assertEquals(40.0, overheadTarget.suggestedWeightKg, 1e-9)
        assertNull(overheadTarget.ghost)
        assertNull(overheadTarget.overload)
    }

    @Test
    fun `prescription progresses lower body lifts by five kilos`() {
        // Legs A squat: 4 sets, 5..6 — all four hit the top (6) at 80 kg.
        val history = listOf(
            workout("s1", "2026-11-08T15:00:00Z", squat to listOf(80.0 to 6, 80.0 to 6, 80.0 to 6, 80.0 to 6)),
        )

        val plan = HistoryReadModels.nextSessionPrescription(TestRoutines.legsA, history, zone)

        assertEquals(85.0, plan.exercises[0].suggestedWeightKg, 1e-9)
        assertEquals(5.0, plan.exercises[0].overload!!.incrementKg, 1e-9)
    }

    @Test
    fun `prescription holds the last lifted weight when a set fell short of the range top`() {
        // One set at 7 of 8: the rule does not fire, and the hold anchors on the
        // 62.5 kg actually lifted — not the routine's stale 60 kg target.
        val history = listOf(
            workout("s1", "2026-11-10T15:00:00Z", bench to listOf(62.5 to 8, 62.5 to 8, 62.5 to 7, 62.5 to 8)),
        )

        val target = HistoryReadModels.nextSessionPrescription(TestRoutines.pushA, history, zone).exercises[0]

        assertEquals(62.5, target.suggestedWeightKg, 1e-9)
        assertNull(target.overload)
        assertNotNull(target.ghost)
    }

    @Test
    fun `prescription holds when the last session was cut short`() {
        // All sets at the top of the range, but only 3 of the prescribed 4.
        val history = listOf(
            workout("s1", "2026-11-10T15:00:00Z", bench to listOf(60.0 to 8, 60.0 to 8, 60.0 to 8)),
        )

        val target = HistoryReadModels.nextSessionPrescription(TestRoutines.pushA, history, zone).exercises[0]

        assertEquals(60.0, target.suggestedWeightKg, 1e-9)
        assertNull(target.overload)
    }

    @Test
    fun `prescription falls back to routine targets with no history`() {
        val plan = HistoryReadModels.nextSessionPrescription(TestRoutines.pullA, emptyList(), zone)

        assertEquals(
            listOf(60.0, 0.0, 12.5),
            plan.exercises.map { it.suggestedWeightKg },
        )
        assertTrue(plan.exercises.all { it.ghost == null && it.overload == null })
        assertTrue(plan.droppedExercises.isEmpty())
    }

    @Test
    fun `prescription is for the substituted exercise under a constraint`() {
        val constraints = constraintsFor(
            HealthProfile(jointLimitations = setOf(JointArea.SHOULDER), onboardingComplete = true),
        )
        // The substitute's own history: landmine press, 3 × 10 at 30 kg — all at
        // the top of the substituted entry's inherited 8..10 range.
        val landmine = ExerciseLibrary.landminePress
        val history = listOf(
            workout("s1", "2026-11-10T15:00:00Z", landmine to listOf(30.0 to 10, 30.0 to 10, 30.0 to 10)),
        )

        val plan = HistoryReadModels.nextSessionPrescription(
            TestRoutines.pushA, history, zone,
            constraints = constraints,
            library = ExerciseLibrary.all,
        )

        val pressTarget = plan.exercises[1]
        assertEquals(landmine.id, pressTarget.exercise.id)
        assertEquals(overhead.id, pressTarget.substitutedFor?.id)
        assertNotNull(pressTarget.ghost)
        assertEquals(landmine.id, pressTarget.ghost!!.exerciseId)
        // Judged on the landmine performance (30 kg, range topped out): +2.5 kg.
        assertEquals(32.5, pressTarget.suggestedWeightKg, 1e-9)
        // The rest of the routine is untouched.
        assertEquals(bench.id, plan.exercises[0].exercise.id)
        assertNull(plan.exercises[0].substitutedFor)
    }

    @Test
    fun `prescription records a dropped exercise instead of prescribing it`() {
        val constraints = FunctionalConstraints(
            exerciseExclusions = listOf(
                ExerciseExclusion(
                    area = JointArea.KNEE,
                    excludedMovements = listOf("Squat"),
                    substitutions = emptyList(),
                    reason = "Test limitation.",
                ),
            ),
            heartRateCapPercentOfMax = null,
            intensityMayOnlyScaleDown = false,
            promptConstraints = emptyList(),
        )

        val plan = HistoryReadModels.nextSessionPrescription(
            TestRoutines.legsA, emptyList(), zone, constraints = constraints,
        )

        assertEquals(3, plan.exercises.size)
        assertEquals(listOf("Squat"), plan.droppedExercises.map { it.exercise.name })
        assertTrue(plan.exercises.none { it.exercise.id == squat.id })
    }

    // -- PR board -----------------------------------------------------------------

    @Test
    fun `pr board keeps the three records straight across sessions`() {
        val s1 = workout(
            "s1", "2026-11-03T15:00:00Z",
            bench to listOf(100.0 to 1, 60.0 to 10),
        )
        val s2 = workout(
            "s2", "2026-11-10T15:00:00Z",
            bench to listOf(90.0 to 6),
        )

        val board = HistoryReadModels.prBoard(listOf(s2, s1), zone).single()

        assertEquals(bench.id, board.exerciseId)
        assertEquals(100.0, board.maxWeight.value, 1e-9)
        assertEquals(LocalDate.of(2026, 11, 3), board.maxWeight.date)
        assertEquals(600.0, board.bestSetVolume.value, 1e-9)
        assertEquals(LocalDate.of(2026, 11, 3), board.bestSetVolume.date)
        // 90 × 6 → Epley 108.0, set in the later session.
        assertEquals(108.0, board.bestEstimated1Rm.value, 1e-9)
        assertEquals(LocalDate.of(2026, 11, 10), board.bestEstimated1Rm.date)
        assertEquals(90.0, board.bestEstimated1Rm.sourceSet?.weightKg)
    }

    @Test
    fun `pr board agrees with the PRs the engine reported on the same history`() {
        val routine = Routine(
            id = "r-bench",
            name = "Bench Only",
            exercises = listOf(RoutineExercise(bench, targetSets = 4, repRange = 6..8, targetWeightKg = 60.0, restSeconds = 120)),
        )

        fun runSession(id: String, startIso: String, date: LocalDate, sets: List<Pair<Double, Int>>, existing: List<PRRecord>): FinishedSession {
            val start = Instant.parse(startIso)
            var state = SessionEngine.start(routine, sessionId = id, startTime = start)
            sets.forEachIndexed { i, (weight, reps) ->
                state = SessionEngine.logSet(state, weightKg = weight, reps = reps, timestamp = start.plusSeconds((i + 1) * 180L)).state
            }
            return SessionEngine.finish(state, endTime = start.plusSeconds(3600), date = date, existingPrs = existing)
        }

        val a = runSession("pa", "2026-11-03T15:00:00Z", LocalDate.of(2026, 11, 3),
            listOf(60.0 to 8, 60.0 to 8, 60.0 to 8, 60.0 to 8), emptyList())
        val b = runSession("pb", "2026-11-10T15:00:00Z", LocalDate.of(2026, 11, 10),
            listOf(62.5 to 8, 62.5 to 8, 62.5 to 6, 60.0 to 8), a.summary.newPrs)
        val enginePrs = a.summary.newPrs + b.summary.newPrs
        assertTrue(enginePrs.isNotEmpty())

        val history = listOf(
            CompletedSession(b.session, b.sets),
            CompletedSession(a.session, a.sets),
        )
        val board = HistoryReadModels.prBoard(history, zone).single { it.exerciseId == bench.id }

        for (type in PrType.entries) {
            val lastEngineRecord = enginePrs.filter { it.exerciseId == bench.id && it.type == type }.last()
            val boardRecord = when (type) {
                PrType.MAX_WEIGHT -> board.maxWeight
                PrType.ESTIMATED_1RM -> board.bestEstimated1Rm
                PrType.MAX_VOLUME -> board.bestSetVolume
            }
            assertEquals(lastEngineRecord.value, boardRecord.value, 1e-9, "$type value")
            assertEquals(lastEngineRecord.date, boardRecord.date, "$type date")
        }
    }

    // -- History screen queries -----------------------------------------------------

    @Test
    fun `recent sessions are newest first with totals and skip unfinished sessions`() {
        val a = workout(
            "sa", "2026-11-03T15:00:00Z",
            bench to listOf(60.0 to 8, 60.0 to 8),
            squat to listOf(80.0 to 5),
            durationMinutes = 60,
        )
        val b = workout(
            "sb", "2026-11-10T15:00:00Z",
            bench to listOf(62.5 to 8),
            durationMinutes = 45,
        )
        val start = Instant.parse("2026-11-11T15:00:00Z")
        val inProgress = CompletedSession(
            session = WorkoutSession(id = "s-live", startTime = start),
            sets = listOf(
                SetEntry(
                    id = "s-live-set-1", sessionId = "s-live", exerciseId = bench.id,
                    exerciseName = bench.name, setNumber = 1, weightKg = 65.0, reps = 8,
                    timestamp = start.plusSeconds(180),
                ),
            ),
        )

        val entries = HistoryReadModels.recentSessions(listOf(b, inProgress, a), zone)

        assertEquals(listOf("sb", "sa"), entries.map { it.session.id })
        assertEquals(LocalDate.of(2026, 11, 10), entries[0].date)
        assertEquals(500.0, entries[0].totalVolumeKg, 1e-9)
        assertEquals(1, entries[0].totalSets)
        assertEquals(2700, entries[0].durationSeconds)
        assertEquals(LocalDate.of(2026, 11, 3), entries[1].date)
        assertEquals(1360.0, entries[1].totalVolumeKg, 1e-9)
        assertEquals(3, entries[1].totalSets)
        assertEquals(3600, entries[1].durationSeconds)
        assertEquals(listOf("Bench Press", "Squat"), entries[1].exerciseNames)
    }

    @Test
    fun `performance series is chronological and merges repeat entries within a session`() {
        val s1 = workout(
            "s1", "2026-11-03T15:00:00Z",
            bench to listOf(60.0 to 8, 60.0 to 6),
        )
        val noBench = workout(
            "s2", "2026-11-05T15:00:00Z",
            overhead to listOf(40.0 to 8),
        )
        // Bench performed in two blocks of one session: the point spans both.
        val s3 = workout(
            "s3", "2026-11-10T15:00:00Z",
            bench to listOf(62.5 to 8),
            overhead to listOf(40.0 to 8),
            bench to listOf(65.0 to 5),
        )

        val series = HistoryReadModels.performanceSeries(listOf(s3, noBench, s1), bench.id, zone)

        assertEquals(2, series.size)
        assertEquals(LocalDate.of(2026, 11, 3), series[0].date)
        assertEquals(60.0, series[0].bestSet.weightKg, 1e-9)
        assertEquals(76.0, series[0].bestEstimated1RmKg, 1e-9)
        assertEquals(840.0, series[0].totalVolumeKg, 1e-9)
        assertEquals(2, series[0].totalSets)

        assertEquals(LocalDate.of(2026, 11, 10), series[1].date)
        assertEquals("s3", series[1].sessionId)
        assertEquals(62.5, series[1].bestSet.weightKg, 1e-9)
        assertEquals(62.5 * (1.0 + 8.0 / 30.0), series[1].bestEstimated1RmKg, 1e-9)
        assertEquals(825.0, series[1].totalVolumeKg, 1e-9)
        assertEquals(2, series[1].totalSets)
    }

    @Test
    fun `performance series is empty for an exercise with no history`() {
        val history = listOf(
            workout("s1", "2026-11-03T15:00:00Z", bench to listOf(60.0 to 8)),
        )
        assertTrue(HistoryReadModels.performanceSeries(history, squat.id, zone).isEmpty())
    }

    // -- Empty history ----------------------------------------------------------------

    @Test
    fun `empty history behaves everywhere`() {
        val empty = emptyList<CompletedSession>()
        assertNull(HistoryReadModels.ghostTarget(empty, bench.id, zone))
        assertTrue(HistoryReadModels.prBoard(empty, zone).isEmpty())
        assertTrue(HistoryReadModels.recentSessions(empty, zone).isEmpty())
        assertTrue(HistoryReadModels.performanceSeries(empty, bench.id, zone).isEmpty())
    }
}
