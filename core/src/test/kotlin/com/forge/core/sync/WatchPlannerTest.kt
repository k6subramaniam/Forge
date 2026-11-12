package com.forge.core.sync

import com.forge.core.data.ExerciseLibrary
import com.forge.core.model.ClearanceState
import com.forge.core.model.HealthProfile
import com.forge.core.model.JointArea
import com.forge.core.model.Routine
import com.forge.core.model.RoutineExercise
import com.forge.core.model.SetEntry
import com.forge.core.model.WorkoutSession
import com.forge.core.onboarding.OnboardingGate
import com.forge.core.training.CompletedSession
import com.forge.core.training.HistoryReadModels
import com.forge.core.training.SessionEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class WatchPlannerTest {

    private val zone = ZoneOffset.UTC
    private val monday: LocalDate = LocalDate.of(2026, 11, 16)
    private val now: Instant = Instant.parse("2026-11-16T06:00:00Z")
    private val ready = HealthProfile(onboardingComplete = true)

    private val push = Routine(
        id = "routine-push",
        name = "Push",
        exercises = listOf(
            RoutineExercise(ExerciseLibrary.benchPress, targetSets = 3, repRange = 8..12, targetWeightKg = 60.0),
            RoutineExercise(ExerciseLibrary.overheadPress, targetSets = 3, repRange = 8..12, targetWeightKg = 40.0),
        ),
    )

    /** Push on Mondays and Thursdays. */
    private fun schedule(date: LocalDate): Routine? =
        push.takeIf { date.dayOfWeek.value == 1 || date.dayOfWeek.value == 4 }

    /** Last Monday's push: every bench set at the top of the range, so bench progresses. */
    private val lastWeek: CompletedSession = run {
        val start = Instant.parse("2026-11-09T07:00:00Z")
        val session = WorkoutSession("s-last", push.id, push.name, start, start.plusSeconds(3_600))
        val sets = (1..3).map { n ->
            SetEntry("s-last-b$n", "s-last", ExerciseLibrary.benchPress.id, "Bench Press", n, 60.0, 12, true, start.plusSeconds(n * 120L))
        }
        CompletedSession(session, sets)
    }

    private fun plan(profile: HealthProfile?, history: List<CompletedSession> = listOf(lastWeek)) =
        WatchPlanner.plan(monday, ::schedule, history, profile, zone, now)

    @Test
    fun `no profile or unfinished onboarding sends nothing to run`() {
        for (profile in listOf(null, HealthProfile(onboardingComplete = false))) {
            val plans = plan(profile)
            assertEquals(WatchPlanner.ONBOARDING_UNFINISHED, plans.blockedReason)
            assertTrue(plans.days.isEmpty() && plans.menu.isEmpty() && plans.existingPrs.isEmpty())
            assertEquals(PlanLookup.Blocked(WatchPlanner.ONBOARDING_UNFINISHED), plans.lookup(monday))
        }
    }

    @Test
    fun `a red flag awaiting clearance sends nothing to run`() {
        val pending = ready.copy(hasExertionalChestPain = true, clearanceState = ClearanceState.REQUIRED)
        val plans = plan(pending)
        assertEquals(WatchPlanner.CLEARANCE_PENDING, plans.blockedReason)
        assertTrue(plans.days.isEmpty() && plans.menu.isEmpty())

        val cleared = plan(pending.copy(clearanceState = ClearanceState.OBTAINED))
        assertNull(cleared.blockedReason)
        assertFalse(cleared.days.isEmpty())
    }

    @Test
    fun `scheduled days carry progression weights and ghost targets`() {
        val plans = plan(ready)
        assertEquals(monday, plans.firstDay)
        assertEquals(WatchPlanner.DAYS, plans.dayCount)
        // Monday and Thursday of the seven days from Monday.
        assertEquals(listOf(monday, monday.plusDays(3)), plans.days.map { it.date })

        val bench = plans.days.first().exercises.first { it.prescription.exercise.id == ExerciseLibrary.benchPress.id }
        // Same numbers the phone's own prescription gives: 60 × 12 on every set → +2.5 kg.
        val expected = HistoryReadModels.nextSessionPrescription(push, listOf(lastWeek), zone)
            .exercises.first { it.exercise.id == ExerciseLibrary.benchPress.id }
        assertEquals(62.5, bench.prescription.targetWeightKg, 1e-9)
        assertEquals(expected.suggestedWeightKg, bench.prescription.targetWeightKg, 1e-9)
        assertEquals(expected.reason, bench.weightReason)
        assertEquals(lastWeek.sets.first(), bench.lastTopSet)
        // Tags exist for constraint matching, which already happened on the phone.
        assertNull(bench.prescription.exercise.tags)
    }

    @Test
    fun `limitations are applied before anything leaves the phone`() {
        val shoulder = ready.copy(jointLimitations = setOf(JointArea.SHOULDER))
        val plans = plan(shoulder)
        val constraints = OnboardingGate.evaluate(shoulder).constraints

        // The scheduled day holds exactly what a live session on the phone would.
        val onPhone = SessionEngine.start(push, "x", now, constraints)
        val day = plans.days.first()
        assertEquals(onPhone.exercises.map { it.exercise.id }, day.exercises.map { it.prescription.exercise.id })
        assertEquals(onPhone.exercises.map { it.substitutedFor?.id }, day.exercises.map { it.substitutedFor?.id })
        assertEquals(onPhone.droppedExercises.map { it.exercise.id }, day.droppedExercises.map { it.exercise.id })
        assertTrue(day.exercises.none { it.prescription.exercise.id == ExerciseLibrary.overheadPress.id })

        // The freeform menu can't offer it either, but still offers what's allowed.
        val menuIds = plans.menu.map { it.exercise.id }
        assertFalse(ExerciseLibrary.overheadPress.id in menuIds)
        assertTrue(ExerciseLibrary.squat.id in menuIds)
        assertTrue(plan(ready).menu.any { it.exercise.id == ExerciseLibrary.overheadPress.id })
    }

    @Test
    fun `the menu carries last time's top set and starts there`() {
        val bench = plan(ready).menu.first { it.exercise.id == ExerciseLibrary.benchPress.id }
        assertEquals(lastWeek.sets.first(), bench.lastTopSet)
        assertEquals(60.0, bench.prescription().targetWeightKg, 1e-9)
        val squat = plan(ready).menu.first { it.exercise.id == ExerciseLibrary.squat.id }
        assertEquals(0.0, squat.prescription().targetWeightKg, 1e-9)
    }

    @Test
    fun `records cover the history, without their source sets`() {
        val prs = plan(ready).existingPrs
        assertEquals(3, prs.size) // max weight, e1RM, set volume for bench
        assertTrue(prs.all { it.exerciseId == ExerciseLibrary.benchPress.id && it.sourceSet == null })
    }

    @Test
    fun `lookup tells scheduled, unscheduled and out-of-date days apart`() {
        val plans = plan(ready)
        assertTrue(plans.lookup(monday) is PlanLookup.Scheduled)
        assertEquals(PlanLookup.Unscheduled, plans.lookup(monday.plusDays(1)))
        assertEquals(PlanLookup.OutOfDate, plans.lookup(monday.plusDays(7)))
        assertEquals(PlanLookup.OutOfDate, plans.lookup(monday.minusDays(1)))
    }

    @Test
    fun `starting a plan holds its exercises, substitutions and drops`() {
        val shoulder = ready.copy(jointLimitations = setOf(JointArea.SHOULDER))
        val day = plan(shoulder).days.first()
        val state = day.start("s-new", now)
        assertEquals("s-new", state.session.id)
        assertEquals(push.id, state.session.routineId)
        assertEquals(now, state.session.startTime)
        assertEquals(0, state.currentExerciseIndex)
        assertEquals(day.exercises.map { it.prescription }, state.exercises.map { it.prescription })
        assertEquals(day.exercises.map { it.substitutedFor }, state.exercises.map { it.substitutedFor })
        assertEquals(day.droppedExercises, state.droppedExercises)
    }
}
