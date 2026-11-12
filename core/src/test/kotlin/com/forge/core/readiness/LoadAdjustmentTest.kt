package com.forge.core.readiness

import com.forge.core.data.ExerciseLibrary
import com.forge.core.testing.TestRoutines
import com.forge.core.model.MovementPattern
import com.forge.core.model.SetEntry
import com.forge.core.model.WorkoutSession
import com.forge.core.onboarding.FunctionalConstraints
import com.forge.core.training.CompletedSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class LoadAdjustmentTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val today: LocalDate = LocalDate.of(2026, 11, 10)

    private val unflagged = FunctionalConstraints(
        exerciseExclusions = emptyList(),
        heartRateCapPercentOfMax = null,
        intensityMayOnlyScaleDown = false,
        promptConstraints = emptyList(),
    )
    private val flagged = unflagged.copy(
        heartRateCapPercentOfMax = 85,
        intensityMayOnlyScaleDown = true,
    )

    private fun readiness(band: ReadinessBand?) = ReadinessAssessment(
        date = today,
        status = if (band == null) ReadinessStatus.INSUFFICIENT_DATA else ReadinessStatus.SCORED,
        band = band,
        score = null,
        compositeZ = null,
        signals = emptyList(),
        missingSignals = emptyList(),
        baselineDaysUsed = 28,
        headline = "",
    )

    private val noPain = PainSignal(emptySet(), emptySet(), null)

    @Test
    fun `PRIMED and unflagged increases via the overload increments`() {
        val adj = LoadAdjuster.recommend(readiness(ReadinessBand.PRIMED), unflagged, noPain)
        assertEquals(SessionAdjustmentKind.INCREASE_LOAD, adj.kind)
        assertTrue(adj.applyOverloadIncrementToTopSets)
        assertEquals(0.0, adj.topSetLoadDeltaPercent)
        assertFalse(adj.cappedByScaleDownOnlyRule)
    }

    @Test
    fun `PRIMED but flagged profile proceeds as prescribed - never up`() {
        val adj = LoadAdjuster.recommend(readiness(ReadinessBand.PRIMED), flagged, noPain)
        assertEquals(SessionAdjustmentKind.PROCEED_AS_PRESCRIBED, adj.kind)
        assertFalse(adj.applyOverloadIncrementToTopSets)
        assertTrue(adj.cappedByScaleDownOnlyRule)
    }

    @Test
    fun `flagged profile never receives an increase under any band or pain state`() {
        val pain = PainSignal(setOf("ex-x"), setOf(MovementPattern.SQUAT), today)
        for (band in ReadinessBand.entries + listOf(null)) {
            for (p in listOf(noPain, pain)) {
                for (patterns in listOf(emptySet(), setOf(MovementPattern.SQUAT), setOf(MovementPattern.HINGE))) {
                    val adj = LoadAdjuster.recommend(readiness(band), flagged, p, patterns)
                    assertTrue(
                        adj.kind != SessionAdjustmentKind.INCREASE_LOAD && !adj.applyOverloadIncrementToTopSets,
                        "flagged profile got an increase: band=$band pain=$p patterns=$patterns",
                    )
                    assertTrue(adj.topSetLoadDeltaPercent <= 0.0)
                }
            }
        }
    }

    @Test
    fun `NORMAL proceeds, STRAINED trims loads, DEPLETED trims loads and sets`() {
        val normal = LoadAdjuster.recommend(readiness(ReadinessBand.NORMAL), unflagged, noPain)
        assertEquals(SessionAdjustmentKind.PROCEED_AS_PRESCRIBED, normal.kind)

        val strained = LoadAdjuster.recommend(readiness(ReadinessBand.STRAINED), unflagged, noPain)
        assertEquals(SessionAdjustmentKind.REDUCE_TOP_SET_LOAD, strained.kind)
        assertEquals(-5.0, strained.topSetLoadDeltaPercent)
        assertEquals(0, strained.setsToDropPerExercise)

        val depleted = LoadAdjuster.recommend(readiness(ReadinessBand.DEPLETED), unflagged, noPain)
        assertEquals(SessionAdjustmentKind.REDUCE_VOLUME, depleted.kind)
        assertEquals(-5.0, depleted.topSetLoadDeltaPercent)
        assertEquals(1, depleted.setsToDropPerExercise)
    }

    @Test
    fun `unscored readiness means proceed as prescribed`() {
        val adj = LoadAdjuster.recommend(readiness(null), unflagged, noPain)
        assertEquals(SessionAdjustmentKind.PROCEED_AS_PRESCRIBED, adj.kind)
        assertNullBand(adj)
    }

    private fun assertNullBand(adj: LoadAdjustment) = assertEquals(null, adj.band)

    @Test
    fun `pain in a pattern trained today forces a technique swap`() {
        val pain = PainSignal(setOf(TestRoutines.squat.id), setOf(MovementPattern.SQUAT), today.minusDays(2))
        val adj = LoadAdjuster.recommend(
            readiness(ReadinessBand.NORMAL), unflagged, pain,
            sessionPatterns = setOf(MovementPattern.SQUAT, MovementPattern.HORIZONTAL_PRESS),
        )
        assertEquals(SessionAdjustmentKind.TECHNIQUE_SWAP, adj.kind)
        assertTrue(adj.painForcedConservative)
        assertEquals(setOf(MovementPattern.SQUAT), adj.painPatterns)
    }

    @Test
    fun `pain forces the conservative path even on a PRIMED day`() {
        val pain = PainSignal(setOf(TestRoutines.squat.id), setOf(MovementPattern.SQUAT), today.minusDays(1))
        val adj = LoadAdjuster.recommend(
            readiness(ReadinessBand.PRIMED), unflagged, pain,
            sessionPatterns = setOf(MovementPattern.SQUAT),
        )
        assertEquals(SessionAdjustmentKind.TECHNIQUE_SWAP, adj.kind)
        assertFalse(adj.applyOverloadIncrementToTopSets)
    }

    @Test
    fun `a technique swap keeps the readiness reductions for the rest of the session`() {
        // Squat pain on a squat-and-press day: squats swap to technique work,
        // and the presses still take what the readiness band calls for.
        val pain = PainSignal(setOf(TestRoutines.squat.id), setOf(MovementPattern.SQUAT), today.minusDays(2))
        val patterns = setOf(MovementPattern.SQUAT, MovementPattern.HORIZONTAL_PRESS)

        val depleted = LoadAdjuster.recommend(readiness(ReadinessBand.DEPLETED), unflagged, pain, patterns)
        assertEquals(SessionAdjustmentKind.TECHNIQUE_SWAP, depleted.kind)
        assertEquals(LoadAdjuster.REDUCED_LOAD_PERCENT, depleted.topSetLoadDeltaPercent, 1e-9)
        assertEquals(LoadAdjuster.REDUCED_SETS_PER_EXERCISE, depleted.setsToDropPerExercise)
        assertTrue("one set fewer" in depleted.reason)

        val strained = LoadAdjuster.recommend(readiness(ReadinessBand.STRAINED), unflagged, pain, patterns)
        assertEquals(SessionAdjustmentKind.TECHNIQUE_SWAP, strained.kind)
        assertEquals(LoadAdjuster.REDUCED_LOAD_PERCENT, strained.topSetLoadDeltaPercent, 1e-9)
        assertEquals(0, strained.setsToDropPerExercise)

        // A good day adds nothing: pain still rules out an increase.
        val primed = LoadAdjuster.recommend(readiness(ReadinessBand.PRIMED), unflagged, pain, patterns)
        assertEquals(0.0, primed.topSetLoadDeltaPercent, 1e-9)
        assertEquals(0, primed.setsToDropPerExercise)
        assertFalse(primed.applyOverloadIncrementToTopSets)
    }

    @Test
    fun `pain with unknown session patterns caps the day at a volume reduction`() {
        val pain = PainSignal(setOf(TestRoutines.squat.id), setOf(MovementPattern.SQUAT), today.minusDays(3))
        val adj = LoadAdjuster.recommend(readiness(ReadinessBand.NORMAL), unflagged, pain)
        assertEquals(SessionAdjustmentKind.REDUCE_VOLUME, adj.kind)
        assertTrue(adj.painForcedConservative)
    }

    @Test
    fun `pain in patterns not trained today changes nothing`() {
        val pain = PainSignal(setOf(TestRoutines.squat.id), setOf(MovementPattern.SQUAT), today.minusDays(1))
        val adj = LoadAdjuster.recommend(
            readiness(ReadinessBand.NORMAL), unflagged, pain,
            sessionPatterns = setOf(MovementPattern.HORIZONTAL_PRESS),
        )
        assertEquals(SessionAdjustmentKind.PROCEED_AS_PRESCRIBED, adj.kind)
        assertFalse(adj.painForcedConservative)
    }

    @Test
    fun `recentPain reads the engine pain flag inside the recency window only`() {
        fun session(id: String, isoStart: String, pain: Boolean): CompletedSession {
            val start = Instant.parse(isoStart)
            return CompletedSession(
                session = WorkoutSession(id = id, startTime = start, endTime = start.plusSeconds(3600)),
                sets = listOf(
                    SetEntry(
                        id = "$id-s1", sessionId = id, exerciseId = TestRoutines.squat.id,
                        exerciseName = TestRoutines.squat.name, setNumber = 1,
                        weightKg = 100.0, reps = 5, timestamp = start, painReported = pain,
                    ),
                ),
            )
        }
        val history = listOf(
            session("recent", "2026-11-08T09:00:00Z", pain = true),  // 2 days before today
            session("old", "2026-10-20T09:00:00Z", pain = true),     // outside the 7-day window
            session("clean", "2026-11-09T09:00:00Z", pain = false),
        )
        val signal = LoadAdjuster.recentPain(history, today, zone)
        assertEquals(setOf(TestRoutines.squat.id), signal.exerciseIds)
        assertEquals(setOf(MovementPattern.SQUAT), signal.patterns)
        assertEquals(LocalDate.of(2026, 11, 8), signal.mostRecentDate)

        val staleOnly = LoadAdjuster.recentPain(listOf(history[1]), today, zone)
        assertTrue(staleOnly.exerciseIds.isEmpty())
    }

    @Test
    fun `recentPain resolves exercises beyond the starter ten by default`() {
        val incline = ExerciseLibrary.inclineDumbbellPress
        val start = Instant.parse("2026-11-08T09:00:00Z")
        val history = listOf(
            CompletedSession(
                WorkoutSession(id = "s", startTime = start, endTime = start.plusSeconds(3600)),
                listOf(SetEntry("s-1", "s", incline.id, incline.name, 1, 22.5, 10, timestamp = start, painReported = true)),
            ),
        )
        assertEquals(setOf(MovementPattern.HORIZONTAL_PRESS), LoadAdjuster.recentPain(history, today, zone).patterns)
    }
}
