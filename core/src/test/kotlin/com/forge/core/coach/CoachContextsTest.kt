package com.forge.core.coach

import com.forge.core.data.ExerciseLibrary
import com.forge.core.model.Exercise
import com.forge.core.model.PRRecord
import com.forge.core.model.PrType
import com.forge.core.model.RoutineExercise
import com.forge.core.model.SetEntry
import com.forge.core.model.WorkoutSession
import com.forge.core.nutrition.DietLog
import com.forge.core.nutrition.FoodLogEntry
import com.forge.core.nutrition.FoodSource
import com.forge.core.nutrition.MacroTargets
import com.forge.core.nutrition.MealType
import com.forge.core.nutrition.Micronutrient
import com.forge.core.nutrition.MicronutrientCoverage
import com.forge.core.nutrition.NutrientValues
import com.forge.core.nutrition.WeightTrend
import com.forge.core.onboarding.FunctionalConstraints
import com.forge.core.readiness.LoadAdjuster
import com.forge.core.readiness.PlateauAssessment
import com.forge.core.readiness.PlateauStatus
import com.forge.core.readiness.ReadinessAssessment
import com.forge.core.readiness.ReadinessBand
import com.forge.core.readiness.ReadinessStatus
import com.forge.core.training.CompletedSession
import com.forge.core.training.ExerciseSummary
import com.forge.core.training.FinishedSession
import com.forge.core.training.NextSessionPrescription
import com.forge.core.training.PrescribedExerciseTarget
import com.forge.core.training.SessionSummary
import com.forge.core.training.WeightUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class CoachContextsTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val today: LocalDate = LocalDate.of(2026, 11, 11)

    private val strainedReadiness = ReadinessAssessment(
        date = today,
        status = ReadinessStatus.SCORED,
        band = ReadinessBand.STRAINED,
        score = 38,
        compositeZ = -0.9,
        signals = emptyList(),
        missingSignals = emptyList(),
        baselineDaysUsed = 28,
        headline = "Strained — readiness is below your baseline; the biggest driver is HRV, 1.4 SD below yours.",
    )

    private val noConstraints = FunctionalConstraints(
        exerciseExclusions = emptyList(),
        heartRateCapPercentOfMax = null,
        intensityMayOnlyScaleDown = false,
        promptConstraints = emptyList(),
    )

    private fun benchPrescription(): NextSessionPrescription {
        val target = PrescribedExerciseTarget(
            prescription = RoutineExercise(
                exercise = ExerciseLibrary.benchPress,
                targetSets = 4,
                repRange = 6..8,
                targetWeightKg = 60.0,
                restSeconds = 120,
            ),
            suggestedWeightKg = 60.0,
            reason = "Last time: 60 kg × 8 on 2026-11-08 — repeat the weight and build reps.",
            ghost = null,
            overload = null,
            substitutedFor = null,
            adjustmentReason = null,
        )
        return NextSessionPrescription(
            routineId = "routine-push-a",
            routineName = "Push A",
            exercises = listOf(target),
            droppedExercises = emptyList(),
        )
    }

    // -- Morning brief ---------------------------------------------------------

    @Test
    fun `morning context carries readiness band and adjustment, rendered in lb by default`() {
        val adjustment = LoadAdjuster.recommend(strainedReadiness, noConstraints)
        val context = CoachContexts.morning(strainedReadiness, benchPrescription(), adjustment)

        val text = context.render(WeightUnit.LB)
        assertTrue(text.contains("MORNING BRIEF"), text)
        assertTrue(text.contains("STRAINED"), text)
        assertTrue(text.contains("score 38"), text)
        assertTrue(text.contains("Bench Press"), text)
        // 60 kg stored canonically; the default display unit is lb (132.3 lb).
        assertTrue(text.contains("132.3 lb"), text)
        assertTrue(text.contains("REDUCE_TOP_SET_LOAD"), text)
        assertTrue(text.contains("take 5% off the top sets"), text)
    }

    @Test
    fun `morning context renders in kg when the preference is kg`() {
        val adjustment = LoadAdjuster.recommend(strainedReadiness, noConstraints)
        val context = CoachContexts.morning(strainedReadiness, benchPrescription(), adjustment)

        val text = context.render(WeightUnit.KG)
        assertTrue(text.contains("60 kg"), text)
        assertFalse(text.contains("132.3"), text)
    }

    @Test
    fun `morning context keeps only plateau verdicts as flags`() {
        val plateau = PlateauAssessment(
            exerciseId = "ex-bench-press",
            exerciseName = "Bench Press",
            status = PlateauStatus.PLATEAU,
            sessionsConsidered = 6,
            slopeKgPerSession = 0.16,
            fittedChangeKg = 0.8,
            peakGainKg = 1.0,
            residualSdKg = 1.5,
            leastSignificantChangeKg = 4.2,
        )
        val progressing = plateau.copy(
            exerciseId = "ex-squat",
            exerciseName = "Squat",
            status = PlateauStatus.PROGRESSING,
            fittedChangeKg = 6.0,
        )
        val adjustment = LoadAdjuster.recommend(strainedReadiness, noConstraints)
        val context = CoachContexts.morning(
            strainedReadiness, benchPrescription(), adjustment,
            plateauAssessments = listOf(plateau, progressing),
        )

        assertEquals(listOf(plateau), context.plateauFlags)
        val text = context.render(WeightUnit.LB)
        assertTrue(text.contains("Stalled lifts"), text)
        // 0.8 kg fitted change renders as +1.8 lb in the user's unit.
        assertTrue(text.contains("+1.8 lb"), text)
        assertFalse(text.contains("Squat"), text)
    }

    @Test
    fun `morning context with unscored readiness says so instead of inventing a band`() {
        val unscored = strainedReadiness.copy(
            status = ReadinessStatus.INSUFFICIENT_DATA,
            band = null,
            score = null,
            compositeZ = null,
            headline = "Not enough baseline yet — readiness needs about 14 days of readings per signal before it can be scored.",
        )
        val adjustment = LoadAdjuster.recommend(unscored, noConstraints)
        val text = CoachContexts.morning(unscored, null, adjustment).render(WeightUnit.LB)
        assertTrue(text.contains("Readiness: not scored"), text)
        assertTrue(text.contains("Today's session: none planned."), text)
    }

    // -- Post-workout debrief ----------------------------------------------------

    @Test
    fun `post-workout context summarises the finished session with its PRs`() {
        val start = Instant.parse("2026-11-11T09:00:00Z")
        val bestSet = SetEntry("set-4", "s1", "ex-bench-press", "Bench Press", 4, 62.5, 5, true, start)
        val finished = FinishedSession(
            session = WorkoutSession(
                id = "s1",
                routineId = "routine-push-a",
                routineName = "Push A",
                startTime = start,
                endTime = start.plusSeconds(2700),
            ),
            sets = listOf(bestSet),
            summary = SessionSummary(
                totalVolumeKg = 4200.0,
                totalSets = 4,
                durationSeconds = 2700,
                exercises = listOf(
                    ExerciseSummary("ex-bench-press", "Bench Press", listOf(bestSet), 1250.0, bestSet),
                ),
                newPrs = listOf(
                    PRRecord("ex-bench-press", "Bench Press", PrType.MAX_WEIGHT, 62.5, today, bestSet),
                ),
                substitutions = emptyList(),
                droppedExercises = emptyList(),
                painExercises = emptyList(),
            ),
        )

        val text = CoachContexts.postWorkout(finished).render(WeightUnit.LB)
        assertTrue(text.contains("POST-WORKOUT DEBRIEF"), text)
        assertTrue(text.contains("45 min"), text)
        // 62.5 kg max-weight PR renders as 137.8 lb.
        assertTrue(text.contains("max weight 137.8 lb"), text)
    }

    // -- Evening diet check-in ---------------------------------------------------

    private fun chickenEntry(id: String, at: String): FoodLogEntry = FoodLogEntry(
        id = id,
        timestamp = Instant.parse(at),
        meal = MealType.LUNCH,
        name = "Chicken breast",
        source = FoodSource.USDA,
        quantityGrams = 200.0,
        nutrientsPer100g = NutrientValues(
            caloriesKcal = 165.0,
            proteinGrams = 31.0,
            carbsGrams = 0.0,
            fatGrams = 3.6,
            fibreGrams = 0.0,
            sugarGrams = 0.0,
            sodiumMg = 74.0,
            // 1.0 µg per 100 g → 2.0 µg in the portion: 13% of the 15 µg reference.
            vitaminDMicrograms = 1.0,
        ),
    )

    @Test
    fun `evening context reflects a genuine micro gap from slice 7 types`() {
        val dayEntries = listOf(chickenEntry("e1", "2026-11-11T12:30:00Z"))
        val totals = DietLog.summarise(dayEntries, today, zone)
        val comparison = DietLog.compare(
            totals,
            MacroTargets(caloriesKcal = 2600, proteinGrams = 170, fatGrams = 72, carbGrams = 318, fibreGrams = 36),
        )
        // Three identically-covered days make vitamin D a persistent gap.
        val gaps = MicronutrientCoverage.gapReport(listOf(dayEntries, dayEntries, dayEntries))

        val context = CoachContexts.evening(totals, comparison, gapReports = gaps)

        assertEquals(listOf(Micronutrient.VITAMIN_D), context.lowMicros.map { it.nutrient })
        assertEquals(listOf(Micronutrient.VITAMIN_D), context.persistentGaps.map { it.nutrient })
        val text = context.render(WeightUnit.LB)
        assertTrue(text.contains("Calories: 330 of 2600 kcal"), text)
        assertTrue(text.contains("Vitamin D: 2 µg of 15 µg reference (13%)"), text)
        assertTrue(text.contains("Persistent gap: Vitamin D — low on 3 of 3 well-covered days"), text)
    }

    @Test
    fun `evening context does not call an unmeasured nutrient low`() {
        // A MyFitnessPal-style entry: macros only, no micronutrients at all.
        val mfpEntry = FoodLogEntry(
            id = "mfp-1",
            timestamp = Instant.parse("2026-11-11T13:00:00Z"),
            meal = MealType.LUNCH,
            name = "Lunch (meal total)",
            source = FoodSource.MYFITNESSPAL,
            quantityGrams = 100.0,
            nutrientsPer100g = NutrientValues(caloriesKcal = 640.0, proteinGrams = 42.0, carbsGrams = 55.0, fatGrams = 27.0),
        )
        val totals = DietLog.summarise(listOf(mfpEntry), today, zone)
        val comparison = DietLog.compare(
            totals,
            MacroTargets(caloriesKcal = 2600, proteinGrams = 170, fatGrams = 72, carbGrams = 318, fibreGrams = 36),
        )
        val context = CoachContexts.evening(totals, comparison)

        assertTrue(context.lowMicros.isEmpty())
        assertTrue(context.render(WeightUnit.LB).contains("no low readings on adequately-covered data"))
    }

    // -- Weekly review -------------------------------------------------------------

    private fun workout(id: String, startIso: String, exercise: Exercise, sets: List<Pair<Double, Int>>): CompletedSession {
        val start = Instant.parse(startIso)
        val entries = sets.mapIndexed { index, (weight, reps) ->
            SetEntry(
                id = "$id-set-${index + 1}",
                sessionId = id,
                exerciseId = exercise.id,
                exerciseName = exercise.name,
                setNumber = index + 1,
                weightKg = weight,
                reps = reps,
                timestamp = start.plusSeconds(index * 180L),
            )
        }
        return CompletedSession(
            session = WorkoutSession(id = id, startTime = start, endTime = start.plusSeconds(3600)),
            sets = entries,
        )
    }

    @Test
    fun `weekly review aggregates sessions, PRs, landmarks and trend`() {
        val history = listOf(
            workout("w1", "2026-11-09T09:00:00Z", ExerciseLibrary.benchPress, listOf(60.0 to 8, 60.0 to 8, 60.0 to 7)),
            workout("w2", "2026-11-11T09:00:00Z", ExerciseLibrary.benchPress, listOf(62.5 to 6, 62.5 to 6, 62.5 to 5)),
        )
        val trend = WeightTrend(
            points = emptyList(),
            weighInCount = 6,
            spanDays = 20,
            latestTrendKg = 81.0,
            weeklyRateKgPerWeek = 0.2,
        )
        val context = CoachContexts.weekly(history, today, zone, trend = trend)

        assertEquals(LocalDate.of(2026, 11, 9), context.weekStart)
        assertEquals(2, context.sessionCount)
        assertEquals(6, context.totalSets)
        assertTrue(context.newPrs.isNotEmpty())
        val text = context.render(WeightUnit.LB)
        assertTrue(text.contains("Training: 2 sessions, 6 sets"), text)
        assertTrue(text.contains("chest"), text)
        // 0.2 kg/week renders as +0.4 lb/week in the user's unit.
        assertTrue(text.contains("+0.4 lb/week"), text)
    }

    @Test
    fun `weekly review with empty history degrades to explicit no-data markers`() {
        val context = CoachContexts.weekly(emptyList(), today, zone)

        assertEquals(0, context.sessionCount)
        assertTrue(context.newPrs.isEmpty())
        val text = context.render(WeightUnit.LB)
        assertTrue(text.contains("Training: no sessions logged this week."), text)
        assertTrue(text.contains("New PRs this week: none."), text)
        assertTrue(text.contains("Bodyweight trend: not enough weigh-ins to state a rate."), text)
        assertTrue(text.contains("Diet: nothing logged this week."), text)
    }
}
