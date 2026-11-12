package com.forge.core.sync

import com.forge.core.data.ExerciseLibrary
import com.forge.core.model.Exercise
import com.forge.core.model.HealthProfile
import com.forge.core.model.Routine
import com.forge.core.model.RoutineExercise
import com.forge.core.onboarding.FunctionalConstraints
import com.forge.core.onboarding.OnboardingGate
import com.forge.core.training.CompletedSession
import com.forge.core.training.HistoryReadModels
import com.forge.core.training.SessionEngine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Builds the [WatchPlans] the phone sends the watch, from the same read-models
 * the phone shows: [HistoryReadModels.nextSessionPrescription] for each
 * scheduled day (progression, ghost targets, substitutions and drops) and
 * [HistoryReadModels.prBoard] for PRs.
 *
 * Safety stays on the phone (blueprint 3.0). The onboarding gate decides
 * whether anything may be planned. When it may not, the watch gets a reason
 * and nothing to run. Limitations are applied here, before anything leaves
 * the phone: scheduled exercises are substituted or dropped, and the
 * freeform menu lists only exercises the limitations allow. The watch never
 * sees the health profile and can't skip a limitation.
 *
 * Exercises travel without their [Exercise.tags]: tags exist for constraint
 * matching, which has already happened.
 */
object WatchPlanner {

    /** Days covered: today and the following six, one full weekly schedule. */
    const val DAYS = 7

    const val ONBOARDING_UNFINISHED = "Finish setting up Forge on your phone to start training."
    const val CLEARANCE_PENDING =
        "Training is on hold until you record medical clearance in Forge on your phone."

    fun plan(
        today: LocalDate,
        routineFor: (LocalDate) -> Routine?,
        history: List<CompletedSession>,
        healthProfile: HealthProfile?,
        zone: ZoneId,
        generatedAt: Instant,
        library: List<Exercise> = ExerciseLibrary.all,
    ): WatchPlans {
        val gate = healthProfile?.takeIf { it.onboardingComplete }?.let(OnboardingGate::evaluate)
        if (gate == null || !gate.canGeneratePlans) {
            return WatchPlans(
                generatedAt = generatedAt,
                firstDay = today,
                dayCount = DAYS,
                days = emptyList(),
                menu = emptyList(),
                existingPrs = emptyList(),
                blockedReason = if (gate == null) ONBOARDING_UNFINISHED else CLEARANCE_PENDING,
            )
        }
        val constraints = gate.constraints

        val days = (0 until DAYS).mapNotNull { offset ->
            val date = today.plusDays(offset.toLong())
            val routine = routineFor(date) ?: return@mapNotNull null
            val next = HistoryReadModels.nextSessionPrescription(routine, history, zone, constraints, library)
            WorkoutPlan(
                date = date,
                routineId = next.routineId,
                routineName = next.routineName,
                exercises = next.exercises.map { target ->
                    PlannedExercise(
                        prescription = target.prescription.copy(
                            exercise = target.prescription.exercise.untagged(),
                            targetWeightKg = target.suggestedWeightKg,
                        ),
                        weightReason = target.reason,
                        lastTopSet = target.ghost?.topSet,
                        substitutedFor = target.substitutedFor?.untagged(),
                        adjustmentReason = target.adjustmentReason,
                    )
                },
                droppedExercises = next.droppedExercises.map { it.copy(exercise = it.exercise.untagged()) },
            )
        }

        return WatchPlans(
            generatedAt = generatedAt,
            firstDay = today,
            dayCount = DAYS,
            days = days,
            menu = allowedExercises(library, constraints).map { exercise ->
                MenuExercise(
                    exercise = exercise.untagged(),
                    lastTopSet = HistoryReadModels.ghostTarget(history, exercise.id, zone)?.topSet,
                )
            },
            existingPrs = HistoryReadModels.prBoard(history, zone).flatMap { board ->
                // The source set isn't needed to compare against a record.
                listOf(board.maxWeight, board.bestEstimated1Rm, board.bestSetVolume).map { it.copy(sourceSet = null) }
            },
            blockedReason = null,
        )
    }

    /**
     * The library exercises a session may add under [constraints], in library
     * order. Each is run through the engine's own constraint step. One it would
     * keep as-is is allowed. One it would substitute or drop is left out (an
     * allowed substitute is listed on its own).
     */
    private fun allowedExercises(
        library: List<Exercise>,
        constraints: FunctionalConstraints,
    ): List<Exercise> {
        val probe = Routine(
            id = "menu",
            name = "menu",
            exercises = library.map { RoutineExercise(it, targetSets = 1, repRange = 1..1, targetWeightKg = 0.0) },
        )
        val kept = SessionEngine.start(probe, "menu", Instant.EPOCH, constraints, library).exercises
            .filter { it.substitutedFor == null }
            .map { it.exercise.id }
            .toSet()
        return library.filter { it.id in kept }
    }

    private fun Exercise.untagged(): Exercise = copy(tags = null)
}
