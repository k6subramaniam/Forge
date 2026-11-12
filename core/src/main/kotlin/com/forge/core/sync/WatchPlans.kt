package com.forge.core.sync

import com.forge.core.model.Exercise
import com.forge.core.model.PRRecord
import com.forge.core.model.RoutineExercise
import com.forge.core.model.SetEntry
import com.forge.core.model.WorkoutSession
import com.forge.core.training.DroppedExercise
import com.forge.core.training.LiveSessionState
import com.forge.core.training.SessionExerciseState
import java.time.Instant
import java.time.LocalDate

/**
 * One exercise of a [WorkoutPlan]: the prescription the watch runs, already
 * through the phone's constraint and progression logic.
 */
data class PlannedExercise(
    /** Target weight is the progression suggestion for this session, not the routine template. */
    val prescription: RoutineExercise,
    /** Why the weight is what it is (the progression rule's reason). */
    val weightReason: String,
    /** The best set last time this exercise was done: the watch's ghost target. */
    val lastTopSet: SetEntry?,
    /** The exercise a limitation swapped out, when this is its substitute. */
    val substitutedFor: Exercise?,
    val adjustmentReason: String?,
)

/** A scheduled routine for one day, ready to run on the watch. */
data class WorkoutPlan(
    val date: LocalDate,
    val routineId: String,
    val routineName: String,
    val exercises: List<PlannedExercise>,
    /** Exercises a limitation removed with no safe substitute, with the reason. */
    val droppedExercises: List<DroppedExercise>,
) {
    /**
     * Starts a live session from this plan. Constraints were applied when the
     * plan was made ([WatchPlanner]), so they aren't applied again: the session
     * holds exactly the plan's exercises, substitutions and drops.
     */
    fun start(sessionId: String, startTime: Instant): LiveSessionState {
        val exercises = exercises.map {
            SessionExerciseState(
                prescription = it.prescription,
                substitutedFor = it.substitutedFor,
                adjustmentReason = it.adjustmentReason,
            )
        }
        return LiveSessionState(
            session = WorkoutSession(
                id = sessionId,
                routineId = routineId,
                routineName = routineName,
                startTime = startTime,
            ),
            exercises = exercises,
            currentExerciseIndex = if (exercises.isEmpty()) null else 0,
            droppedExercises = droppedExercises,
        )
    }
}

/**
 * An exercise the watch may add to a session (freeform, or growing a routine
 * session). Only exercises the user's limitations allow are listed.
 */
data class MenuExercise(
    val exercise: Exercise,
    val lastTopSet: SetEntry?,
) {
    /** The prescription an added exercise starts with: last time's top weight, or empty bar/bodyweight. */
    fun prescription(): RoutineExercise = RoutineExercise(
        exercise = exercise,
        targetSets = FREEFORM_SETS,
        repRange = FREEFORM_REPS,
        targetWeightKg = lastTopSet?.weightKg ?: 0.0,
    )

    companion object {
        const val FREEFORM_SETS = 3
        val FREEFORM_REPS = 8..12
    }
}

/**
 * Everything the phone sends the watch (blueprint 3.6: the phone is the source
 * of truth; the watch runs sessions phone-free in the gym).
 *
 * The phone sends a window of days rather than just today, so the watch still
 * knows what to run if the phone isn't nearby that morning. Plans are rebuilt
 * whenever the phone learns something that changes them: a session arriving
 * from the watch, a profile change, the app opening.
 */
data class WatchPlans(
    val generatedAt: Instant,
    /** The window covered: [firstDay] and the [dayCount] − 1 days after it. */
    val firstDay: LocalDate,
    val dayCount: Int,
    /** One plan per scheduled day in the window. Days without one are unscheduled. */
    val days: List<WorkoutPlan>,
    val menu: List<MenuExercise>,
    /** Current records for every exercise with history, so the watch can call PRs at finish. */
    val existingPrs: List<PRRecord>,
    /**
     * Set when training may not be planned at all (onboarding unfinished, or a
     * red flag awaiting medical clearance). The watch shows it and offers no
     * session. [days] and [menu] are empty then.
     */
    val blockedReason: String?,
) {
    /** What the watch can offer on [date]. */
    fun lookup(date: LocalDate): PlanLookup {
        blockedReason?.let { return PlanLookup.Blocked(it) }
        val inWindow = !date.isBefore(firstDay) && date.isBefore(firstDay.plusDays(dayCount.toLong()))
        if (!inWindow) return PlanLookup.OutOfDate
        return days.firstOrNull { it.date == date }?.let { PlanLookup.Scheduled(it) } ?: PlanLookup.Unscheduled
    }
}

sealed interface PlanLookup {
    data class Scheduled(val plan: WorkoutPlan) : PlanLookup

    /** Nothing scheduled that day; a freeform session is still possible. */
    data object Unscheduled : PlanLookup

    /** The plans don't reach this date: the phone hasn't refreshed them recently. Freeform still works. */
    data object OutOfDate : PlanLookup

    data class Blocked(val reason: String) : PlanLookup
}
