package com.forge.core.training

import com.forge.core.data.ExerciseLibrary
import com.forge.core.model.Exercise
import com.forge.core.model.PRRecord
import com.forge.core.model.PrType
import com.forge.core.model.Routine
import com.forge.core.model.RoutineExercise
import com.forge.core.model.SetEntry
import com.forge.core.model.WorkoutSession
import com.forge.core.onboarding.FunctionalConstraints
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One completed workout as persisted: the [WorkoutSession] row plus its sets.
 *
 * This small adapter exists because it is exactly the stored shape (blueprint 3.6:
 * the phone's Room database holds sessions and sets as separate rows), while
 * [FinishedSession] — the engine's output — also carries the summary, which is
 * derivable and not stored. It maps one-to-one onto a [FinishedSession]'s first
 * two fields, so engine-produced sessions feed straight in. Contract: [sets]
 * holds one session's sets with each exercise's sets in logging order (the same
 * grouping [FinishedSession.sets] already has), and only sessions with an end
 * time count as history — in-progress sessions are ignored by every query here.
 */
data class CompletedSession(
    val session: WorkoutSession,
    val sets: List<SetEntry>,
)

/**
 * The ghost target for one exercise (blueprint 3.1): what the watch shows before
 * today's first set — "last time: 60 kg × 8".
 */
data class GhostTarget(
    val exerciseId: String,
    val exerciseName: String,
    /** The session the performance came from, and its local date. */
    val sessionId: String,
    val date: LocalDate,
    /**
     * The set to beat: the session's best set by estimated 1RM (Epley), ties
     * broken by set volume — the same "best set" definition the finish-time
     * summary uses ([ExerciseSummary.bestSet]).
     */
    val topSet: SetEntry,
    /** Every completed set logged for the exercise in that session, in logging order. */
    val sets: List<SetEntry>,
)

/** One exercise's suggested starting point for the next session (blueprint 3.2). */
data class PrescribedExerciseTarget(
    /**
     * The routine entry after hard constraints were applied (blueprint 3.0):
     * the exercise here can differ from the routine's when a limitation
     * substituted it — see [substitutedFor].
     */
    val prescription: RoutineExercise,
    /** Where to start today: progressed, held, or the prescribed fallback weight. */
    val suggestedWeightKg: Double,
    /** Plain-language explanation for the UI, in the overload rule's style. */
    val reason: String,
    /** The last performance this target was derived from, or null when there is none. */
    val ghost: GhostTarget?,
    /** The overload rule's suggestion when it fired; null on a hold or a fallback. */
    val overload: OverloadSuggestion?,
    /** The routine's original exercise when a constraint substituted this one. */
    val substitutedFor: Exercise?,
    /** Why the prescription differs from the routine, when it does (blueprint 3.0). */
    val adjustmentReason: String?,
) {
    val exercise: Exercise get() = prescription.exercise
}

/** A whole routine's next-session targets (blueprint 3.2). */
data class NextSessionPrescription(
    val routineId: String,
    val routineName: String,
    val exercises: List<PrescribedExerciseTarget>,
    /** Exercises constraints removed outright, with reasons — recorded, never silent. */
    val droppedExercises: List<DroppedExercise>,
)

/**
 * One exercise's current personal records (blueprint 3.3): heaviest weight, best
 * estimated 1RM, and best single-set volume, each the [PRRecord] that set it —
 * so the board shows the value and the date it fell on.
 */
data class ExercisePrBoard(
    val exerciseId: String,
    val exerciseName: String,
    val maxWeight: PRRecord,
    val bestEstimated1Rm: PRRecord,
    val bestSetVolume: PRRecord,
)

/** One row of the phone's History list (blueprint 3.3). */
data class SessionHistoryEntry(
    val session: WorkoutSession,
    val date: LocalDate,
    /** Exercise names in the order they were performed in the session. */
    val exerciseNames: List<String>,
    /** Completed sets only — the ones that count toward volume (engine rule). */
    val totalSets: Int,
    val totalVolumeKg: Double,
    val durationSeconds: Long,
)

/** One point on an exercise's progress chart (blueprint 3.3). */
data class ExercisePerformancePoint(
    val date: LocalDate,
    val sessionId: String,
    /** The session's best set for the exercise (estimated-1RM definition, as in [GhostTarget.topSet]). */
    val bestSet: SetEntry,
    val bestEstimated1RmKg: Double,
    val totalSets: Int,
    val totalVolumeKg: Double,
)

/**
 * History read-models (blueprint 3.1/3.2/3.3): pure functions that turn past
 * workouts into what the watch and phone display — ghost targets on the logger,
 * next-session starting weights, the PR board, and the History screen's rows
 * and charts. No state, no I/O: the UIs hand in the persisted sessions and
 * render what comes back.
 *
 * Consistency rules, so the read-models never disagree with the live engine:
 * - Only completed sets count, everywhere (the engine's rule for volume/PRs).
 * - "Best set" is always highest estimated 1RM (Epley), ties broken by volume —
 *   the [ExerciseSummary] definition.
 * - PRs are folded session by session through [PrDetector] exactly as
 *   [SessionEngine.finish] detects them, so the board is precisely the records
 *   the engine celebrated at each finish, kept current.
 *
 * Dates are local dates in the caller's [ZoneId] (the engine takes dates as
 * parameters for the same reason: core stays clock- and timezone-free).
 */
object HistoryReadModels {

    // -- Ghost targets (blueprint 3.1) ----------------------------------------

    /**
     * The most recent prior performance of [exerciseId]: the newest completed
     * session with at least one completed set for it — most recent, not best
     * ever. Null when the exercise has never been performed.
     */
    fun ghostTarget(
        history: List<CompletedSession>,
        exerciseId: String,
        zone: ZoneId,
    ): GhostTarget? {
        val source = chronological(history).lastOrNull { completedSetsFor(it, exerciseId).isNotEmpty() }
            ?: return null
        val sets = completedSetsFor(source, exerciseId)
        return GhostTarget(
            exerciseId = exerciseId,
            exerciseName = sets.first().exerciseName,
            sessionId = source.session.id,
            date = source.session.startTime.atZone(zone).toLocalDate(),
            topSet = checkNotNull(bestSetOf(sets)),
            sets = sets,
        )
    }

    // -- Next-session prescription (blueprint 3.2) -----------------------------

    /**
     * Suggested starting targets for [routine]'s next session.
     *
     * Constraints are applied by running the routine through the session
     * engine's own start path, so a prescription can never name an exercise a
     * live session would refuse: an excluded exercise is prescribed as its
     * substitute (and judged against the substitute's own history), or dropped
     * with the reason recorded in [NextSessionPrescription.droppedExercises].
     *
     * Per exercise: with history, the last session is judged by
     * [ProgressiveOverload] at the weight actually lifted — top of the rep
     * range on every set progresses by the rule's increment, anything short
     * holds at the last top-set weight. Without history, the routine's
     * prescribed target is the fallback.
     */
    fun nextSessionPrescription(
        routine: Routine,
        history: List<CompletedSession>,
        zone: ZoneId,
        constraints: FunctionalConstraints = SessionEngine.NO_CONSTRAINTS,
        library: List<Exercise> = ExerciseLibrary.all,
    ): NextSessionPrescription {
        // The engine state is used purely as the constraint-resolution step;
        // the placeholder id/time never surface (nothing is logged or finished).
        val plan = SessionEngine.start(
            routine = routine,
            sessionId = PLANNING_SESSION_ID,
            startTime = Instant.EPOCH,
            constraints = constraints,
            library = library,
        )
        val targets = plan.exercises.map { entry ->
            val prescription = entry.prescription
            val ghost = ghostTarget(history, entry.exercise.id, zone)
            when {
                ghost == null -> PrescribedExerciseTarget(
                    prescription = prescription,
                    suggestedWeightKg = prescription.targetWeightKg,
                    reason = "No history for '${entry.exercise.name}' yet — start at the prescribed target.",
                    ghost = null,
                    overload = null,
                    substitutedFor = entry.substitutedFor,
                    adjustmentReason = entry.adjustmentReason,
                )
                else -> {
                    // Judge the weight actually lifted last time: the overload
                    // rule progresses the working weight, not a stale routine
                    // target the lifter may already have moved past.
                    val judgedTarget = prescription.copy(targetWeightKg = ghost.topSet.weightKg)
                    val suggestion = ProgressiveOverload.suggest(entry.exercise, judgedTarget, ghost.sets)
                    if (suggestion != null) {
                        PrescribedExerciseTarget(
                            prescription = prescription,
                            suggestedWeightKg = suggestion.suggestedWeightKg,
                            reason = suggestion.reason,
                            ghost = ghost,
                            overload = suggestion,
                            substitutedFor = entry.substitutedFor,
                            adjustmentReason = entry.adjustmentReason,
                        )
                    } else {
                        PrescribedExerciseTarget(
                            prescription = prescription,
                            suggestedWeightKg = ghost.topSet.weightKg,
                            reason = "Last time: ${formatKg(ghost.topSet.weightKg)} kg × ${ghost.topSet.reps} " +
                                "on ${ghost.date} — repeat the weight and build reps.",
                            ghost = ghost,
                            overload = null,
                            substitutedFor = entry.substitutedFor,
                            adjustmentReason = entry.adjustmentReason,
                        )
                    }
                }
            }
        }
        return NextSessionPrescription(
            routineId = routine.id,
            routineName = routine.name,
            exercises = targets,
            droppedExercises = plan.droppedExercises,
        )
    }

    // -- PR board (blueprint 3.3) ----------------------------------------------

    /**
     * Current personal records for every exercise in [history], in order of
     * first appearance. Detection replays the history session by session
     * through [PrDetector] — the same calls [SessionEngine.finish] makes, with
     * records carried forward — so the board always agrees with the PRs the
     * engine reported at finish time on the same workouts.
     */
    fun prBoard(history: List<CompletedSession>, zone: ZoneId): List<ExercisePrBoard> {
        var accumulated: List<PRRecord> = emptyList()
        val latest = mutableMapOf<Pair<String, PrType>, PRRecord>()
        val names = mutableMapOf<String, String>()
        val order = mutableListOf<String>()
        for (completed in chronological(history)) {
            val date = completed.session.startTime.atZone(zone).toLocalDate()
            val setsByExercise = completed.sets.filter { it.completed }.groupBy { it.exerciseId }
            for ((exerciseId, sets) in setsByExercise) {
                if (exerciseId !in names) {
                    names[exerciseId] = sets.first().exerciseName
                    order += exerciseId
                }
                val newPrs = PrDetector.detectNewPrs(sets, accumulated, date)
                for (pr in newPrs) latest[exerciseId to pr.type] = pr
                accumulated = accumulated + newPrs
            }
        }
        // An exercise reaches the board only via a completed set, and a first
        // completed set always sets all three records — hence getValue is safe.
        return order.map { exerciseId ->
            ExercisePrBoard(
                exerciseId = exerciseId,
                exerciseName = names.getValue(exerciseId),
                maxWeight = latest.getValue(exerciseId to PrType.MAX_WEIGHT),
                bestEstimated1Rm = latest.getValue(exerciseId to PrType.ESTIMATED_1RM),
                bestSetVolume = latest.getValue(exerciseId to PrType.MAX_VOLUME),
            )
        }
    }

    // -- History screen queries (blueprint 3.3) ---------------------------------

    /** Past sessions, newest first, with the totals the History list shows. */
    fun recentSessions(history: List<CompletedSession>, zone: ZoneId): List<SessionHistoryEntry> =
        chronological(history).asReversed().map { completed ->
            val completedSets = completed.sets.filter { it.completed }
            SessionHistoryEntry(
                session = completed.session,
                date = completed.session.startTime.atZone(zone).toLocalDate(),
                exerciseNames = completed.sets.map { it.exerciseName }.distinct(),
                totalSets = completedSets.size,
                totalVolumeKg = completedSets.sumOf { it.volumeKg },
                durationSeconds = Duration.between(
                    completed.session.startTime,
                    checkNotNull(completed.session.endTime),
                ).seconds,
            )
        }

    /**
     * One point per session that performed [exerciseId], oldest first (chart
     * order). An exercise performed twice in one session merges into a single
     * point — its best set and totals span both entries, matching how
     * [SessionEngine.finish] summarises duplicates.
     */
    fun performanceSeries(
        history: List<CompletedSession>,
        exerciseId: String,
        zone: ZoneId,
    ): List<ExercisePerformancePoint> =
        chronological(history).mapNotNull { completed ->
            val sets = completedSetsFor(completed, exerciseId)
            if (sets.isEmpty()) {
                null
            } else {
                val best = checkNotNull(bestSetOf(sets))
                ExercisePerformancePoint(
                    date = completed.session.startTime.atZone(zone).toLocalDate(),
                    sessionId = completed.session.id,
                    bestSet = best,
                    bestEstimated1RmKg = OneRepMax.estimateEpley(best.weightKg, best.reps),
                    totalSets = sets.size,
                    totalVolumeKg = sets.sumOf { it.volumeKg },
                )
            }
        }

    // -- Internals ---------------------------------------------------------------

    private const val PLANNING_SESSION_ID = "next-session-plan"

    /** Completed sessions only, oldest first — the fold order for every query. */
    private fun chronological(history: List<CompletedSession>): List<CompletedSession> =
        history.filter { it.session.isCompleted }.sortedBy { it.session.startTime }

    private fun completedSetsFor(completed: CompletedSession, exerciseId: String): List<SetEntry> =
        completed.sets.filter { it.completed && it.exerciseId == exerciseId }

    /** The shared "best set" definition: highest estimated 1RM, ties broken by volume. */
    private fun bestSetOf(sets: List<SetEntry>): SetEntry? =
        sets.maxWithOrNull(
            compareBy<SetEntry> { OneRepMax.estimateEpley(it.weightKg, it.reps) }
                .thenBy { it.volumeKg },
        )

    /** Weights as lifters write them: 60 rather than 60.0, 62.5 as-is. */
    private fun formatKg(weightKg: Double): String =
        if (weightKg == weightKg.toInt().toDouble()) weightKg.toInt().toString() else weightKg.toString()
}
