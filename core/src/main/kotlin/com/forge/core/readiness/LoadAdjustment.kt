package com.forge.core.readiness

import com.forge.core.data.SampleData
import com.forge.core.model.Exercise
import com.forge.core.model.MovementPattern
import com.forge.core.onboarding.FunctionalConstraints
import com.forge.core.training.CompletedSession
import java.time.LocalDate
import java.time.ZoneId

/** What today's session should become, in increasing order of conservatism. */
enum class SessionAdjustmentKind {
    /** Top band, unflagged profile: progress the top sets by the overload increments. */
    INCREASE_LOAD,

    /** Train exactly as prescribed. */
    PROCEED_AS_PRESCRIBED,

    /** Keep the shape of the session; take weight off the top sets. */
    REDUCE_TOP_SET_LOAD,

    /** Drop a set per exercise (and load, modestly) — a genuinely scaled-back day. */
    REDUCE_VOLUME,

    /** Affected movement patterns swap to technique work; the rest proceeds. */
    TECHNIQUE_SWAP,
}

/**
 * The deterministic session adjustment the coach reads (blueprint 3.8:
 * "on low-readiness days the AI offers a scaled version of today's session").
 * It is a *description* of the scaling, not a rewritten session: the session
 * engine / UI applies it to the prescription. Percentages are of each
 * exercise's prescribed top-set weight.
 */
data class LoadAdjustment(
    val kind: SessionAdjustmentKind,
    /** The readiness band behind the call; null when readiness couldn't be scored. */
    val band: ReadinessBand?,
    /** Signed % change for top-set weights (e.g. −5.0); 0.0 when loads are untouched. */
    val topSetLoadDeltaPercent: Double,
    /** True only for [SessionAdjustmentKind.INCREASE_LOAD]: progress top sets by the 3.2 increments. */
    val applyOverloadIncrementToTopSets: Boolean,
    /** Sets to remove from every exercise (0 unless volume is being reduced). */
    val setsToDropPerExercise: Int,
    /** True when recent pain changed the outcome versus the readiness table alone. */
    val painForcedConservative: Boolean,
    /** Movement patterns with recent pain reports — the ones a technique swap applies to. */
    val painPatterns: Set<MovementPattern>,
    /** Exercise ids with recent pain reports, including untagged ones whose pattern is unknown. */
    val painExerciseIds: Set<String>,
    /** True when the 3.0 scale-down-only rule capped an increase the band would have allowed. */
    val cappedByScaleDownOnlyRule: Boolean,
    /** Plain-language explanation for the coach and the UI. */
    val reason: String,
)

/**
 * Recent pain found in history (blueprint 3.0's in-workout feedback loop: a
 * pain report is a hard signal, not a statistic). [patterns] only contains
 * exercises the library tags; [exerciseIds] catches the rest.
 */
data class PainSignal(
    val exerciseIds: Set<String>,
    val patterns: Set<MovementPattern>,
    val mostRecentDate: LocalDate?,
)

/**
 * Load adjustment rules (blueprint 3.15 "autoregulation", 3.8 "readiness and
 * auto-regulation", 3.0 propagation rule 2). The mapping is a table, not a
 * judgement call — the coach quotes it, it does not improvise around it:
 *
 * | Band / state          | Adjustment                                  |
 * |-----------------------|---------------------------------------------|
 * | PRIMED, unflagged     | increase top sets by the overload rule      |
 * | PRIMED, flagged (3.0) | proceed as prescribed (increase capped)     |
 * | NORMAL                | proceed as prescribed                       |
 * | STRAINED              | top-set loads −5%                           |
 * | DEPLETED              | −5% loads and one set fewer per exercise    |
 * | INSUFFICIENT_DATA     | proceed as prescribed (no data, no change)  |
 *
 * Two overlays outrank the table:
 * - **Pain.** Pain reported within [PAIN_RECENCY_DAYS] in a movement pattern
 *   that appears in today's session forces a technique swap for that pattern;
 *   when today's patterns are unknown, recent pain caps the day at a volume
 *   reduction instead. Pain in patterns today's session doesn't train
 *   changes nothing. Either way, pain blocks any increase outright.
 * - **Scale-down-only profiles** (blueprint 3.0: cardiovascular flags or
 *   HR-affecting medication, as the gate's
 *   [FunctionalConstraints.intensityMayOnlyScaleDown] expresses it) can never
 *   be adjusted up, whatever the band says.
 *
 * The step sizes are documented choices, flagged for Kumar's review: the
 * blueprint and evidence base mandate *that* low-readiness days scale the
 * session (3.8) but publish no percentage. −5% is the smallest load step a
 * plate-loaded barbell resolves cleanly at typical working weights and the
 * conventional "one small step down" in RIR-based autoregulation practice
 * (3.15 names RIR/RPE autoregulation without fixing numbers); the DEPLETED
 * row pairs it with the volume lever rather than a deeper cut, per the
 * evidence base's one-lever-at-a-time discipline (A25/A26, Evidence Base v1).
 */
object LoadAdjuster {

    /** Load step for scaled days (see the class KDoc for its status as a chosen default). */
    const val REDUCED_LOAD_PERCENT = -5.0

    /** Sets dropped per exercise on a volume-reduced day. */
    const val REDUCED_SETS_PER_EXERCISE = 1

    /**
     * How long a pain report keeps forcing the conservative path. A week
     * covers the next scheduled occurrence of the same session in a typical
     * split; the standing-limitation flow (blueprint 3.0) is the mechanism
     * for anything longer-lived. Documented choice, flagged for review.
     */
    const val PAIN_RECENCY_DAYS = 7L

    /**
     * Pain reported in the [PAIN_RECENCY_DAYS] days before [today] (inclusive),
     * from completed sessions only. Patterns resolve through [library]'s tags;
     * exercises missing from the library or untagged contribute their id only.
     */
    fun recentPain(
        history: List<CompletedSession>,
        today: LocalDate,
        zone: ZoneId,
        library: List<Exercise> = SampleData.exercises,
    ): PainSignal {
        val windowStart = today.minusDays(PAIN_RECENCY_DAYS)
        val byId = library.associateBy { it.id }
        val exerciseIds = mutableSetOf<String>()
        val patterns = mutableSetOf<MovementPattern>()
        var mostRecent: LocalDate? = null
        for (completed in history) {
            if (!completed.session.isCompleted) continue
            val date = completed.session.startTime.atZone(zone).toLocalDate()
            if (date < windowStart || date > today) continue
            val painSets = completed.sets.filter { it.painReported }
            if (painSets.isEmpty()) continue
            if (mostRecent == null || date > mostRecent) mostRecent = date
            for (set in painSets) {
                exerciseIds += set.exerciseId
                byId[set.exerciseId]?.tags?.pattern?.let { patterns += it }
            }
        }
        return PainSignal(exerciseIds, patterns, mostRecent)
    }

    /**
     * The adjustment for today's session.
     *
     * @param sessionPatterns the movement patterns today's planned session
     * contains, when the caller knows them (from the prescription); empty
     * means "unknown", in which case recent pain applies its generic cap.
     */
    fun recommend(
        readiness: ReadinessAssessment,
        constraints: FunctionalConstraints,
        pain: PainSignal = PainSignal(emptySet(), emptySet(), null),
        sessionPatterns: Set<MovementPattern> = emptySet(),
    ): LoadAdjustment {
        val scaleDownOnly = constraints.intensityMayOnlyScaleDown
        var capped = false

        // The readiness table (see class KDoc).
        var kind = when (readiness.band) {
            ReadinessBand.PRIMED -> if (scaleDownOnly) {
                capped = true
                SessionAdjustmentKind.PROCEED_AS_PRESCRIBED
            } else {
                SessionAdjustmentKind.INCREASE_LOAD
            }
            ReadinessBand.NORMAL, null -> SessionAdjustmentKind.PROCEED_AS_PRESCRIBED
            ReadinessBand.STRAINED -> SessionAdjustmentKind.REDUCE_TOP_SET_LOAD
            ReadinessBand.DEPLETED -> SessionAdjustmentKind.REDUCE_VOLUME
        }

        // The pain overlay: never an increase, and matched patterns swap outright.
        // Pain in patterns today's session doesn't train changes nothing; pain
        // whose patterns can't be compared (session unknown) caps the day at a
        // volume reduction — unknown is treated cautiously, per blueprint 3.0.
        val painActive = pain.exerciseIds.isNotEmpty()
        val painIntersectsSession = sessionPatterns.intersect(pain.patterns).isNotEmpty()
        val kindBeforePain = kind
        if (painActive) {
            kind = when {
                painIntersectsSession -> SessionAdjustmentKind.TECHNIQUE_SWAP
                sessionPatterns.isEmpty() &&
                    kind.ordinal < SessionAdjustmentKind.REDUCE_VOLUME.ordinal ->
                    SessionAdjustmentKind.REDUCE_VOLUME
                else -> kind
            }
        }
        val painForced = painActive && kind != kindBeforePain

        val reason = buildString {
            append(
                when (kind) {
                    SessionAdjustmentKind.INCREASE_LOAD ->
                        "Readiness is PRIMED and your profile allows scaling up — progress top sets by the usual increments."
                    SessionAdjustmentKind.PROCEED_AS_PRESCRIBED -> when {
                        capped -> "Readiness is PRIMED, but your profile is scale-down-only (blueprint 3.0) — train as prescribed, no increase."
                        readiness.band == null -> "Readiness couldn't be scored yet — train as prescribed; no data, no change."
                        painActive -> "Readiness is fine, and recent pain doesn't touch today's patterns — train as prescribed."
                        else -> "Readiness is in line with your baseline — train as prescribed."
                    }
                    SessionAdjustmentKind.REDUCE_TOP_SET_LOAD ->
                        "Readiness is STRAINED — keep the session, take 5% off the top sets."
                    SessionAdjustmentKind.REDUCE_VOLUME ->
                        if (painActive && readiness.band != ReadinessBand.DEPLETED) {
                            "Pain was reported in the last $PAIN_RECENCY_DAYS days — scale back: 5% off the loads and one set fewer per exercise."
                        } else {
                            "Readiness is DEPLETED — scale back: 5% off the loads and one set fewer per exercise."
                        }
                    SessionAdjustmentKind.TECHNIQUE_SWAP -> {
                        val names = sessionPatterns.intersect(pain.patterns)
                            .joinToString(", ") { it.name.lowercase().replace('_', ' ') }
                        "Pain was reported in the last $PAIN_RECENCY_DAYS days in today's $names work — swap those lifts to technique work; the rest proceeds."
                    }
                },
            )
        }

        return LoadAdjustment(
            kind = kind,
            band = readiness.band,
            topSetLoadDeltaPercent = when (kind) {
                SessionAdjustmentKind.REDUCE_TOP_SET_LOAD,
                SessionAdjustmentKind.REDUCE_VOLUME,
                -> REDUCED_LOAD_PERCENT
                else -> 0.0
            },
            applyOverloadIncrementToTopSets = kind == SessionAdjustmentKind.INCREASE_LOAD,
            setsToDropPerExercise = if (kind == SessionAdjustmentKind.REDUCE_VOLUME) {
                REDUCED_SETS_PER_EXERCISE
            } else {
                0
            },
            painForcedConservative = painForced,
            painPatterns = pain.patterns,
            painExerciseIds = pain.exerciseIds,
            cappedByScaleDownOnlyRule = capped,
            reason = reason,
        )
    }
}
