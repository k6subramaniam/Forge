package com.forge.core.training

import com.forge.core.data.ExerciseLibrary
import com.forge.core.model.Exercise
import com.forge.core.model.PRRecord
import com.forge.core.model.Routine
import com.forge.core.model.RoutineExercise
import com.forge.core.model.SetEntry
import com.forge.core.model.WorkoutSession
import com.forge.core.onboarding.ExerciseExclusion
import com.forge.core.onboarding.FunctionalConstraints
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * The live session engine (blueprint 3.1): start a routine or freeform session, log
 * sets, navigate exercises, undo, and finish with a summary. Pure functions over
 * [LiveSessionState] — every operation takes a state and returns a new one, so the
 * watch and phone UIs stay thin shells and the whole flow is unit-testable.
 *
 * Limitations from onboarding are hard constraints here (blueprint 3.0): an excluded
 * exercise is never prescribed. It is substituted from the constraint's own
 * substitution list when a safe one exists, and dropped — with the reason recorded
 * in [LiveSessionState.droppedExercises] — when it doesn't. The gate that decides
 * whether plans may be generated at all stays in
 * com.forge.core.onboarding.OnboardingGate; this engine assumes generation was allowed
 * and enforces the resulting [FunctionalConstraints] on every prescription, including
 * exercises added freeform mid-session.
 *
 * Pain is the in-workout half of the same rule (blueprint 3.0): a set logged with a
 * pain flag stays in the record, and its exercise is swapped out for the rest of the
 * session. Whether the pain becomes a standing profile limitation is a UI/profile
 * decision — the engine only surfaces it via [SessionSummary.painExercises].
 */
object SessionEngine {

    /** Empty constraint set for profiles without limitations (and for tests). */
    val NO_CONSTRAINTS: FunctionalConstraints = FunctionalConstraints(
        exerciseExclusions = emptyList(),
        heartRateCapPercentOfMax = null,
        intensityMayOnlyScaleDown = false,
        promptConstraints = emptyList(),
    )

    // -- Starting ------------------------------------------------------------

    /**
     * Starts a routine-follow session (blueprint 3.1), applying [constraints] to every
     * prescribed exercise before the first set is logged.
     *
     * [library] resolves substitution names (e.g. "Leg press") to real exercises when
     * they exist in the library; unmatched substitution names become generated
     * exercises that keep the original's muscle group and equipment. It defaults to
     * the full tagged [ExerciseLibrary]: a substitute resolved to its tagged entry
     * is checked against the restrictions, while a generated, untagged one could
     * slip past them.
     */
    fun start(
        routine: Routine,
        sessionId: String,
        startTime: Instant,
        constraints: FunctionalConstraints = NO_CONSTRAINTS,
        library: List<Exercise> = ExerciseLibrary.all,
    ): LiveSessionState {
        val exercises = mutableListOf<SessionExerciseState>()
        val dropped = mutableListOf<DroppedExercise>()
        for (prescribed in routine.exercises) {
            when (val outcome = applyConstraints(prescribed, constraints, library)) {
                is ConstraintOutcome.Kept ->
                    exercises += SessionExerciseState(outcome.prescription)
                is ConstraintOutcome.Substituted ->
                    exercises += SessionExerciseState(
                        prescription = outcome.prescription,
                        substitutedFor = outcome.original,
                        adjustmentReason = outcome.reason,
                    )
                is ConstraintOutcome.Dropped ->
                    dropped += DroppedExercise(outcome.original, outcome.reason)
            }
        }
        return LiveSessionState(
            session = WorkoutSession(
                id = sessionId,
                routineId = routine.id,
                routineName = routine.name,
                startTime = startTime,
            ),
            exercises = exercises,
            currentExerciseIndex = if (exercises.isEmpty()) null else 0,
            droppedExercises = dropped,
        )
    }

    /** Starts a freeform session (blueprint 3.1): no routine; exercises are added as you go. */
    fun startFreeform(sessionId: String, startTime: Instant): LiveSessionState =
        LiveSessionState(
            session = WorkoutSession(id = sessionId, startTime = startTime),
        )

    /**
     * Adds an exercise to the session (the freeform flow; also how a routine session
     * grows mid-workout). The same hard constraints apply as at [start]: an excluded
     * exercise is substituted or dropped rather than prescribed.
     */
    fun addExercise(
        state: LiveSessionState,
        prescription: RoutineExercise,
        constraints: FunctionalConstraints = NO_CONSTRAINTS,
        library: List<Exercise> = ExerciseLibrary.all,
    ): LiveSessionState {
        requireActive(state)
        return when (val outcome = applyConstraints(prescription, constraints, library)) {
            is ConstraintOutcome.Kept ->
                appendExercise(state, SessionExerciseState(outcome.prescription))
            is ConstraintOutcome.Substituted ->
                appendExercise(
                    state,
                    SessionExerciseState(
                        prescription = outcome.prescription,
                        substitutedFor = outcome.original,
                        adjustmentReason = outcome.reason,
                    ),
                )
            is ConstraintOutcome.Dropped ->
                state.copy(droppedExercises = state.droppedExercises + DroppedExercise(outcome.original, outcome.reason))
        }
    }

    private fun appendExercise(state: LiveSessionState, exercise: SessionExerciseState): LiveSessionState {
        val grown = state.copy(exercises = state.exercises + exercise)
        val newIndex = grown.exercises.lastIndex
        // Point at the new exercise when nothing else is left to do, unless the
        // exercise was already removed this session after a pain report. It is
        // still appended, so undoing that pain set brings it back.
        val current = if ((state.currentExerciseIndex == null || state.allWorkComplete) && grown.isAvailable(newIndex)) {
            newIndex
        } else {
            state.currentExerciseIndex
        }
        return grown.copy(currentExerciseIndex = current)
    }

    // -- Logging -------------------------------------------------------------

    /**
     * Logs a completed set against the current exercise (blueprint 3.1) and returns
     * the rest prescription for it ([RestTimerLogic], started from the performed
     * exercise's rest duration) — null when the set finished all remaining work.
     *
     * Logging the set that completes an exercise's prescription — or any set with
     * [painReported] — advances the session to the next available exercise. Extra
     * sets beyond a completed prescription can still be logged after navigating
     * back with [goToExercise]. Set ids are derived deterministically from the
     * session id and log order, so a set re-logged after an undo reuses the id.
     */
    fun logSet(
        state: LiveSessionState,
        weightKg: Double,
        reps: Int,
        timestamp: Instant,
        rpe: Double? = null,
        rir: Int? = null,
        painReported: Boolean = false,
    ): LogSetResult {
        requireActive(state)
        require(!state.isPaused) { "Session is paused; resume before logging a set" }
        require(weightKg >= 0) { "Weight cannot be negative, was $weightKg" }
        require(reps >= 1) { "Reps must be at least 1, was $reps" }
        require(rpe == null || rpe in 0.0..10.0) { "RPE must be between 0 and 10, was $rpe" }
        require(rir == null || rir >= 0) { "RIR cannot be negative, was $rir" }
        val index = checkNotNull(state.currentExerciseIndex) {
            "No current exercise to log against; add or select an exercise first"
        }
        val current = state.exercises[index]
        require(state.isAvailable(index)) {
            "'${current.exercise.name}' is not available in this session (skipped, or removed after a pain report)"
        }

        val set = SetEntry(
            id = "${state.session.id}-set-${state.setHistory.size + 1}",
            sessionId = state.session.id,
            exerciseId = current.exercise.id,
            exerciseName = current.exercise.name,
            setNumber = current.loggedSets.size + 1,
            weightKg = weightKg,
            reps = reps,
            completed = true,
            timestamp = timestamp,
            rpe = rpe,
            rir = rir,
            painReported = painReported,
        )

        val updated = current.copy(loggedSets = current.loggedSets + set)
        val logged = state.copy(
            exercises = state.exercises.toMutableList().also { it[index] = updated },
            setHistory = state.setHistory + set.id,
        )
        val exerciseJustCompleted = !current.isComplete && updated.isComplete

        // Pain swaps the exercise out immediately (every entry of it, see
        // LiveSessionState.isRemovedForPain); completing the prescription moves on
        // as well. Either way the pointer lands on the next exercise with work
        // left, or nowhere when the session's work is done.
        val nextIndex = if (painReported || exerciseJustCompleted) {
            nextExerciseIndex(logged, fromIndex = index)
        } else {
            index
        }

        val newState = logged.copy(currentExerciseIndex = nextIndex)
        return LogSetResult(
            state = newState,
            loggedSet = set,
            rest = if (newState.allWorkComplete) null else RestTimerLogic.startFor(updated.prescription),
            exerciseJustCompleted = exerciseJustCompleted,
            allWorkComplete = newState.allWorkComplete,
        )
    }

    /**
     * Removes the most recently logged set, wherever it sits (blueprint 3.1: undo
     * last set), and points the logger back at its exercise so the corrected set
     * can be logged. Like [goToExercise], returning to a skipped exercise un-skips
     * it. If the exercise is still pain-removed (another pain set remains), the
     * pointer stays on available work instead.
     */
    fun undoLastSet(state: LiveSessionState): LiveSessionState {
        requireActive(state)
        val lastSetId = state.setHistory.lastOrNull() ?: return state
        val exerciseIndex = state.exercises.indexOfFirst { ex -> ex.loggedSets.any { it.id == lastSetId } }
        if (exerciseIndex < 0) return state // defensive; history and sets are kept in sync
        val undone = state.copy(
            exercises = state.exercises.toMutableList().also { list ->
                val ex = list[exerciseIndex]
                list[exerciseIndex] = ex.copy(
                    loggedSets = ex.loggedSets.filterNot { it.id == lastSetId },
                    skipped = false,
                )
            },
            setHistory = state.setHistory.dropLast(1),
        )
        val current = when {
            undone.isAvailable(exerciseIndex) -> exerciseIndex
            state.currentExerciseIndex?.let { undone.isAvailable(it) } == true -> state.currentExerciseIndex
            else -> nextExerciseIndex(undone, fromIndex = exerciseIndex)
        }
        return undone.copy(currentExerciseIndex = current)
    }

    // -- Navigation ----------------------------------------------------------

    /** Moves the pointer to the next exercise with work left, without skipping the current one. */
    fun moveToNext(state: LiveSessionState): LiveSessionState {
        requireActive(state)
        val from = state.currentExerciseIndex ?: return state
        val next = nextExerciseIndex(state, fromIndex = from) ?: return state
        return state.copy(currentExerciseIndex = next)
    }

    /** Skips the current exercise (its logged sets still count) and moves on (blueprint 3.1). */
    fun skipExercise(state: LiveSessionState): LiveSessionState {
        requireActive(state)
        val index = state.currentExerciseIndex ?: return state
        val skipped = state.copy(
            exercises = state.exercises.toMutableList().also { it[index] = it[index].copy(skipped = true) },
        )
        return skipped.copy(currentExerciseIndex = nextExerciseIndex(skipped, fromIndex = index))
    }

    /**
     * Points the logger at an earlier (or any) exercise without losing logged sets.
     * Going back to a skipped exercise un-skips it. A pain-removed exercise stays
     * removed — pain is a hard constraint for the rest of the session (blueprint 3.0).
     */
    fun goToExercise(state: LiveSessionState, index: Int): LiveSessionState {
        requireActive(state)
        require(index in state.exercises.indices) { "No exercise at index $index" }
        val target = state.exercises[index]
        require(!state.isRemovedForPain(index)) {
            "'${target.exercise.name}' was removed from this session after a pain report"
        }
        val exercises = if (target.skipped) {
            state.exercises.toMutableList().also { it[index] = target.copy(skipped = false) }
        } else {
            state.exercises
        }
        return state.copy(exercises = exercises, currentExerciseIndex = index)
    }

    /**
     * The next exercise with prescribed work left: the first available,
     * uncompleted exercise after [fromIndex], wrapping around to the ones
     * before it. The wrap matters when the user advanced early — finishing or
     * skipping the last exercise must land back on an unfinished earlier one,
     * not strand the session with no current exercise. The exercise at
     * [fromIndex] itself is never a candidate: callers invoke this because it
     * just completed, was skipped, or was pain-removed, and each of those also
     * fails the availability/completion checks below.
     */
    private fun nextExerciseIndex(state: LiveSessionState, fromIndex: Int): Int? {
        fun hasWorkLeft(i: Int) = state.isAvailable(i) && !state.exercises[i].isComplete
        return ((fromIndex + 1) until state.exercises.size).firstOrNull { hasWorkLeft(it) }
            ?: (0 until fromIndex).firstOrNull { hasWorkLeft(it) }
    }

    // -- Session controls ------------------------------------------------------

    fun pause(state: LiveSessionState): LiveSessionState {
        requireActive(state)
        return state.copy(isPaused = true)
    }

    fun resume(state: LiveSessionState): LiveSessionState {
        requireActive(state)
        return state.copy(isPaused = false)
    }

    // -- Finishing -------------------------------------------------------------

    /**
     * Ends the session (blueprint 3.1): produces the completed [WorkoutSession] —
     * the same model the phone persists and writes to Health Connect — plus the
     * summary, with PRs detected per exercise against [existingPrs] via
     * [PrDetector] (see [detectSessionPrs] for how duplicate entries of one
     * exercise are handled).
     *
     * [date] is the user's local date for the PR records; the engine takes it as a
     * parameter rather than reading a clock or a timezone.
     */
    fun finish(
        state: LiveSessionState,
        endTime: Instant,
        date: LocalDate,
        existingPrs: List<PRRecord> = emptyList(),
        avgHeartRateBpm: Int? = null,
        maxHeartRateBpm: Int? = null,
        heartRateRecoveryBpm: Int? = null,
        notes: String? = null,
    ): FinishedSession {
        requireActive(state)
        require(endTime.isAfter(state.session.startTime)) { "Session end must be after its start" }

        val completedSession = state.session.copy(
            endTime = endTime,
            notes = notes ?: state.session.notes,
            avgHeartRateBpm = avgHeartRateBpm,
            maxHeartRateBpm = maxHeartRateBpm,
            heartRateRecoveryBpm = heartRateRecoveryBpm,
        )

        val exerciseSummaries = state.exercises.mapNotNull { ex ->
            val completed = ex.completedSets
            if (completed.isEmpty()) {
                null
            } else {
                ExerciseSummary(
                    exerciseId = ex.exercise.id,
                    exerciseName = ex.exercise.name,
                    sets = completed,
                    totalVolumeKg = completed.sumOf { it.volumeKg },
                    bestSet = completed.maxWithOrNull(
                        compareBy<SetEntry> { OneRepMax.estimateEpley(it.weightKg, it.reps) }
                            .thenBy { it.volumeKg },
                    ),
                )
            }
        }

        val summary = SessionSummary(
            totalVolumeKg = state.totalVolumeKg,
            totalSets = state.exercises.sumOf { it.completedSets.size },
            durationSeconds = Duration.between(state.session.startTime, endTime).seconds,
            exercises = exerciseSummaries,
            newPrs = detectSessionPrs(state, existingPrs, date),
            substitutions = state.exercises.mapNotNull { ex ->
                ex.substitutedFor?.let { original ->
                    Substitution(
                        originalExerciseName = original.name,
                        replacementExerciseName = ex.exercise.name,
                        reason = ex.adjustmentReason ?: "",
                    )
                }
            },
            droppedExercises = state.droppedExercises,
            painExercises = state.exercises.filter { it.painReported }.map { it.exercise.name }.distinct(),
        )

        return FinishedSession(
            session = completedSession,
            sets = state.allSets,
            summary = summary,
        )
    }

    /**
     * PR detection for a finished session (blueprint 3.1/3.3). Sets are grouped
     * by exercise across the whole session — not checked entry by entry —
     * because the same exercise can appear twice (a repeated block in the
     * routine, or an exercise added again freeform): checking each entry
     * against the same history in isolation let a weaker set in a later entry
     * be reported as a PR the session itself had already beaten. Within an
     * exercise, sets are checked in logging order (per
     * [LiveSessionState.setHistory]) and [PrDetector] carries its bests
     * forward, so records established earlier in this session count when later
     * sets are compared. The result is ordered by each exercise's first
     * appearance in the session, then by the order its records were set.
     */
    private fun detectSessionPrs(
        state: LiveSessionState,
        existingPrs: List<PRRecord>,
        date: LocalDate,
    ): List<PRRecord> {
        val logOrder = state.setHistory.withIndex().associate { (position, setId) -> setId to position }
        return state.exercises
            .flatMap { it.completedSets }
            .groupBy { it.exerciseId }
            .values
            .flatMap { sets ->
                val inLogOrder = sets.sortedBy { logOrder[it.id] ?: Int.MAX_VALUE }
                PrDetector.detectNewPrs(inLogOrder, existingPrs, date)
            }
    }

    // -- Constraints (blueprint 3.0) -------------------------------------------

    private sealed interface ConstraintOutcome {
        data class Kept(val prescription: RoutineExercise) : ConstraintOutcome
        data class Substituted(
            val prescription: RoutineExercise,
            val original: Exercise,
            val reason: String,
        ) : ConstraintOutcome
        data class Dropped(val original: Exercise, val reason: String) : ConstraintOutcome
    }

    private fun applyConstraints(
        prescribed: RoutineExercise,
        constraints: FunctionalConstraints,
        library: List<Exercise>,
    ): ConstraintOutcome {
        val (exclusion, matched) = matchExclusion(prescribed.exercise, constraints)
            ?: return ConstraintOutcome.Kept(prescribed)

        // First substitution that is not itself excluded under any active
        // constraint. Candidates are resolved against the library before being
        // vetted, so a substitute that only *sounds* safe is still rejected when
        // its tags carry a restricted trait.
        val substituteName = exclusion.substitutions.firstOrNull { candidate ->
            matchExclusion(resolveCandidate(candidate, prescribed.exercise, library), constraints) == null
        } ?: return ConstraintOutcome.Dropped(
            original = prescribed.exercise,
            reason = "'${prescribed.exercise.name}' is excluded ($matched). " +
                "${exclusion.reason} No safe substitution is available, so it was dropped from the session.",
        )

        val substitute = resolveCandidate(substituteName, prescribed.exercise, library)
        return ConstraintOutcome.Substituted(
            prescription = prescribed.copy(exercise = substitute),
            original = prescribed.exercise,
            reason = "'${prescribed.exercise.name}' is excluded ($matched). " +
                "${exclusion.reason} Substituted with '${substitute.name}'.",
        )
    }

    /**
     * Finds the first active exclusion covering [exercise], paired with what
     * matched it: the restriction's plain-language description for a tag match,
     * or the excluded movement's name (quoted) for a name match.
     *
     * Matching runs on tags first (blueprint 3.0): an exercise that carries
     * [com.forge.core.model.ExerciseTags] is judged by its movement pattern and
     * loading traits, which is what separates a barbell row (unsupported) from a
     * chest-supported row — same pattern, different traits, opposite verdicts.
     * The name matcher below remains as the fallback for exercises without tags:
     * user-created exercises start untagged, and free-text limitations a user
     * types in onboarding can only ever be expressed as names.
     */
    private fun matchExclusion(
        exercise: Exercise,
        constraints: FunctionalConstraints,
    ): Pair<ExerciseExclusion, String>? {
        for (exclusion in constraints.exerciseExclusions) {
            val tags = exercise.tags
            if (tags != null) {
                val rule = exclusion.restrictions.firstOrNull { it.matches(tags) }
                if (rule != null) return exclusion to rule.description
            }
            val movement = exclusion.excludedMovements.firstOrNull { nameMatches(exercise.name, it) }
            if (movement != null) return exclusion to "'$movement'"
        }
        return null
    }

    /**
     * Resolves a substitution name from the constraint data (plain language) to
     * an exercise. Resolution order:
     * 1. Exact normalized name match in [library] ("Leg press" → Leg Press).
     * 2. The library exercise whose name appears inside a descriptive phrase
     *    ("Box squat to comfortable depth", "Box squat above parallel" → Box
     *    Squat); the longest such name wins, and single-word names never
     *    resolve this way — "Squat" inside a phrase is too ambiguous to trust.
     * 3. Otherwise a generated exercise carrying the original's muscle group
     *    and equipment, with the prescription (sets, rep range, weight, rest)
     *    unchanged — the weight is a starting point the user adjusts on the
     *    watch steppers.
     */
    private fun resolveCandidate(name: String, original: Exercise, library: List<Exercise>): Exercise {
        library.firstOrNull { normalize(it.name) == normalize(name) }?.let { return it }
        val candidateTokens = nameTokens(name)
        library.filter { ex ->
            val tokens = nameTokens(ex.name)
            tokens.size >= 2 && candidateTokens.containsAll(tokens)
        }.maxByOrNull { nameTokens(it.name).size }?.let { return it }
        return Exercise(
            id = "substitute-" + slug(name),
            name = name,
            muscleGroup = original.muscleGroup,
            equipment = original.equipment,
            isCustom = false,
        )
    }

    /**
     * Name matching for the onboarding exclusions (blueprint 3.0) — the fallback
     * path, used for untagged exercises and for the exclusion data's plain-
     * language movement names. An exercise counts as excluded when either
     * name's words are a subset of the other's: "Squat" matches "Deep squat",
     * "Overhead Press" matches "Overhead press". Deliberately conservative:
     * "Barbell Row" does not match "Heavy unsupported row", because a name alone
     * cannot tell a supported row from an unsupported one. Tagged exercises
     * never depend on that guess — the UNSUPPORTED_ROW trait decides it.
     */
    private fun nameMatches(exerciseName: String, movementName: String): Boolean {
        val a = nameTokens(exerciseName)
        val b = nameTokens(movementName)
        if (a.isEmpty() || b.isEmpty()) return false
        return a.containsAll(b) || b.containsAll(a)
    }

    private fun nameTokens(name: String): Set<String> =
        name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()

    private fun normalize(name: String): String = nameTokens(name).joinToString(" ")

    private fun slug(name: String): String =
        name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.joinToString("-")

    private fun requireActive(state: LiveSessionState) {
        check(!state.isFinished) { "Session '${state.session.id}' is already finished" }
    }
}
