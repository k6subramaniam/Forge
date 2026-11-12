package com.forge.core.training

import com.forge.core.model.Exercise
import com.forge.core.model.PRRecord
import com.forge.core.model.RoutineExercise
import com.forge.core.model.SetEntry
import com.forge.core.model.WorkoutSession

/**
 * One exercise inside a live session: its (possibly substituted) prescription plus
 * everything logged against it so far (blueprint 3.1). Immutable — [SessionEngine]
 * returns a new copy on every change.
 */
data class SessionExerciseState(
    val prescription: RoutineExercise,
    val loggedSets: List<SetEntry> = emptyList(),
    /** The user skipped this exercise; sets already logged against it still count. */
    val skipped: Boolean = false,
    /** Set when a hard constraint replaced the routine's original exercise (blueprint 3.0). */
    val substitutedFor: Exercise? = null,
    /** Why the prescription differs from the routine, in plain language for the UI to show. */
    val adjustmentReason: String? = null,
) {
    val exercise: Exercise get() = prescription.exercise

    /**
     * True once any set in THIS entry was logged with a pain flag (blueprint 3.0).
     * Pain removal applies to the exercise across the whole session, not just this
     * entry: see [LiveSessionState.isRemovedForPain].
     */
    val painReported: Boolean get() = loggedSets.any { it.painReported }

    /** All prescribed sets are logged. Extra sets beyond the target remain loggable. */
    val isComplete: Boolean get() = loggedSets.size >= prescription.targetSets

    /** Completed sets only — the ones that count toward volume and PRs. */
    val completedSets: List<SetEntry> get() = loggedSets.filter { it.completed }

    val totalVolumeKg: Double get() = completedSets.sumOf { it.volumeKg }
}

/**
 * An exercise dropped when a session started because a hard constraint excluded it
 * and no safe substitution existed (blueprint 3.0). Recorded, never silent.
 */
data class DroppedExercise(
    val exercise: Exercise,
    val reason: String,
)

/**
 * Immutable state of a session in progress (blueprint 3.1). The UIs hold this in
 * Compose state / a ViewModel and replace it wholesale with the result of each
 * [SessionEngine] operation — the engine itself does no I/O and keeps no state.
 */
data class LiveSessionState(
    /** The session being built; [WorkoutSession.endTime] stays null until finish. */
    val session: WorkoutSession,
    val exercises: List<SessionExerciseState> = emptyList(),
    /** Index into [exercises] the logger is pointed at, or null when nothing is current. */
    val currentExerciseIndex: Int? = null,
    val droppedExercises: List<DroppedExercise> = emptyList(),
    val isPaused: Boolean = false,
    /** Ids of all logged sets in log order; the tail is what undo removes (blueprint 3.1). */
    val setHistory: List<String> = emptyList(),
) {
    val currentExercise: SessionExerciseState? get() = currentExerciseIndex?.let { exercises.getOrNull(it) }

    /** Every logged set, grouped by exercise in session order. */
    val allSets: List<SetEntry> get() = exercises.flatMap { it.loggedSets }

    val totalVolumeKg: Double get() = exercises.sumOf { it.totalVolumeKg }

    /**
     * Exercises removed for the rest of the session after a pain report (blueprint
     * 3.0's in-workout feedback loop). Removal is by exercise, not by entry: when a
     * routine has the same exercise twice, or it is added again mid-session, pain
     * in one entry removes every entry of it. Derived from the logged sets, so
     * undoing the pain set restores them all.
     */
    val painRemovedExerciseIds: Set<String>
        get() = exercises.filter { it.painReported }.mapTo(HashSet()) { it.exercise.id }

    /** The exercise at [index] was removed after a pain report in any entry of it. */
    fun isRemovedForPain(index: Int): Boolean = exercises[index].exercise.id in painRemovedExerciseIds

    /** The engine will prescribe and log against the exercise at [index]: not skipped, not pain-removed. */
    fun isAvailable(index: Int): Boolean = !exercises[index].skipped && !isRemovedForPain(index)

    /** True when every available exercise has all its prescribed sets logged. */
    val allWorkComplete: Boolean
        get() = exercises.indices.all { !isAvailable(it) || exercises[it].isComplete }

    val isFinished: Boolean get() = session.endTime != null
}

/** Result of logging one set: the new state plus what the UI needs next (blueprint 3.1). */
data class LogSetResult(
    val state: LiveSessionState,
    val loggedSet: SetEntry,
    /** Rest to start now (the performed exercise's prescription), or null when no work remains. */
    val rest: RestTimerState?,
    /** The set just logged completed its exercise's prescription. */
    val exerciseJustCompleted: Boolean,
    /** No prescribed work remains anywhere in the session after this set. */
    val allWorkComplete: Boolean,
)

/** Per-exercise rollup in a finished session's summary (blueprint 3.1/3.3). */
data class ExerciseSummary(
    val exerciseId: String,
    val exerciseName: String,
    val sets: List<SetEntry>,
    val totalVolumeKg: Double,
    /** Best completed set: highest estimated 1RM (Epley), ties broken by set volume. */
    val bestSet: SetEntry?,
)

/** A constraint-driven substitution applied when the session was planned (blueprint 3.0). */
data class Substitution(
    val originalExerciseName: String,
    val replacementExerciseName: String,
    val reason: String,
)

/**
 * Session summary (blueprint 3.1): duration, total volume, sets, bests, and any new
 * PRs — the numbers the watch celebrates on the spot and the phone shows in history.
 */
data class SessionSummary(
    val totalVolumeKg: Double,
    val totalSets: Int,
    val durationSeconds: Long,
    val exercises: List<ExerciseSummary>,
    val newPrs: List<PRRecord>,
    val substitutions: List<Substitution>,
    val droppedExercises: List<DroppedExercise>,
    /** Names of exercises where pain was logged during the session (blueprint 3.0). */
    val painExercises: List<String>,
)

/**
 * The output of finishing a session: the completed [WorkoutSession] (with its sets,
 * ready to persist and to write to Health Connect — blueprint 3.6) plus its summary.
 */
data class FinishedSession(
    val session: WorkoutSession,
    val sets: List<SetEntry>,
    val summary: SessionSummary,
)
