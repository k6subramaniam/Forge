package com.forge.core.readiness

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One day's recovery observations, as read from Health Connect / passive watch
 * monitoring (blueprint 3.4). Every field is nullable: sensors miss nights,
 * and a missing signal must narrow the assessment, never be invented.
 */
data class DailyRecoveryObservation(
    val date: LocalDate,
    /** Morning HRV as RMSSD in milliseconds — the primary readiness signal (blueprint 3.15). */
    val hrvRmssdMs: Double?,
    val restingHeartRateBpm: Int?,
    val sleepDurationMinutes: Int?,
    /** Sleep quality as supplied by the source, normalised to 0.0 (worst) – 1.0 (best). */
    val sleepQualityScore: Double?,
)

/** The recovery signals a readiness assessment can be built from. */
enum class RecoverySignal {
    HRV,
    RESTING_HEART_RATE,
    SLEEP_DURATION,
    SLEEP_QUALITY,
}

/** Whether a readiness number exists at all. */
enum class ReadinessStatus {
    SCORED,
    INSUFFICIENT_DATA,
}

/**
 * The overall readiness band (blueprint 3.8: the morning briefing's headline).
 * Bands are ranges of the composite z-score — see [Readiness].
 */
enum class ReadinessBand {
    PRIMED,
    NORMAL,
    STRAINED,
    DEPLETED,
}

/** One signal's deviation from the user's own baseline, in the assessment's terms. */
data class SignalAssessment(
    val signal: RecoverySignal,
    /** Today's raw value (RHR in bpm, sleep in minutes, quality 0–1, HRV in ms). */
    val todayValue: Double,
    val baselineMean: Double,
    val baselineSd: Double,
    /** Baseline observations this signal was judged against. */
    val baselineCount: Int,
    /**
     * Deviation in baseline SDs, signed so positive always means *better than
     * usual* (HRV/sleep up is good; resting HR up is bad, so its sign flips).
     * Clamped to ±[Readiness.Z_CLAMP] so one wild reading can't dominate.
     */
    val favorabilityZ: Double,
    /** Share of the composite this signal carried (weights renormalised over present signals). */
    val weight: Double,
    /** [favorabilityZ] × [weight] — the ranking key the assessment sorts by. */
    val contribution: Double,
)

/**
 * The morning readiness assessment (blueprint 3.8/3.15). When [status] is
 * [ReadinessStatus.INSUFFICIENT_DATA], [band], [score] and [compositeZ] are
 * null — the honest answer is "not enough baseline yet", never a made-up score.
 */
data class ReadinessAssessment(
    val date: LocalDate,
    val status: ReadinessStatus,
    val band: ReadinessBand?,
    /** Display score 0–100 derived from [compositeZ]; null when not scored. */
    val score: Int?,
    /** Weighted mean of the signals' favorability z-scores; null when not scored. */
    val compositeZ: Double?,
    /** Per-signal detail, ranked by absolute contribution — the "why" behind the band. */
    val signals: List<SignalAssessment>,
    /** Signals with no usable baseline or no value today. */
    val missingSignals: List<RecoverySignal>,
    /** Distinct baseline days (any signal) inside the baseline window. */
    val baselineDaysUsed: Int,
    /** Plain-language one-liner for the coach and the UI, in the house style. */
    val headline: String,
)

/**
 * Readiness scoring (blueprint 3.15: "readiness from HRV (RMSSD) scored against
 * *his own* rolling baseline (z-scores), per HRV-guided training research";
 * sleep signals feed readiness per Maruszczak et al. 2025, Evidence Base v3).
 *
 * Method: each of today's signals is expressed as a z-score against the user's
 * own rolling baseline — the [BASELINE_WINDOW_DAYS] days before today — then
 * combined into one composite z, HRV-weighted. This is the standard
 * HRV-guided-training construction (e.g. Plews et al.'s lnRMSSD rolling-mean
 * monitoring): the athlete is their own reference, so no population table is
 * involved. Forge scores raw RMSSD rather than its log; the z-score against a
 * personal baseline is the part the research hangs on, and the log transform
 * is flagged in [BASELINE_WINDOW_DAYS]'s note as a refinement candidate.
 *
 * Guardrails against noise-chasing:
 * - A signal only counts with at least [MIN_BASELINE_OBSERVATIONS] baseline
 *   observations; if no signal qualifies, the whole assessment is
 *   INSUFFICIENT_DATA.
 * - Each signal's z is clamped to ±[Z_CLAMP], and sleep carries a minority of
 *   the weight — one bad night moves the composite, it cannot crater it.
 *   (HRV is measured on waking and already reflects the night.)
 */
object Readiness {

    /**
     * Baseline window: the 28 days before today. ~4 weeks is the usual
     * HRV-baseline convention in the HRV-guided training literature (a rolling
     * mean/SD long enough to be stable, short enough to track fitness drift).
     * Choice flagged for review: scoring lnRMSSD instead of raw RMSSD is the
     * more common research form; raw RMSSD is used here because Health Connect
     * sources report RMSSD and the personal z-score dominates the transform's
     * effect at this window length.
     */
    const val BASELINE_WINDOW_DAYS = 28

    /** Minimum baseline observations before a signal may be scored at all. */
    const val MIN_BASELINE_OBSERVATIONS = 14

    /** Per-signal z clamp — bounds any single signal's pull on the composite. */
    const val Z_CLAMP = 2.0

    /** Composite-z band edges: PRIMED at/above, DEPLETED below the outer edges. */
    const val PRIMED_Z = 0.5
    const val STRAINED_Z = -0.5
    const val DEPLETED_Z = -1.5

    /**
     * Signal weights before renormalisation over the signals actually present.
     * HRV is primary (blueprint 3.15 names RMSSD as the readiness input);
     * resting HR next; sleep duration and quality share the remainder so a
     * single bad night — already reflected in the morning HRV — is not
     * double-counted into a cratered score.
     */
    val SIGNAL_WEIGHTS: Map<RecoverySignal, Double> = mapOf(
        RecoverySignal.HRV to 0.50,
        RecoverySignal.RESTING_HEART_RATE to 0.20,
        RecoverySignal.SLEEP_DURATION to 0.20,
        RecoverySignal.SLEEP_QUALITY to 0.10,
    )

    /**
     * Baseline SD floors, in each signal's units. A metronome-steady baseline
     * (SD ≈ 0) would otherwise turn a trivial wobble into a huge z; the floor
     * is the smallest deviation worth calling a deviation.
     */
    private val SD_FLOORS: Map<RecoverySignal, Double> = mapOf(
        RecoverySignal.HRV to 1.0,
        RecoverySignal.RESTING_HEART_RATE to 1.0,
        RecoverySignal.SLEEP_DURATION to 10.0,
        RecoverySignal.SLEEP_QUALITY to 0.02,
    )

    /** Whether higher raw values are better (resting HR is the exception). */
    private fun higherIsBetter(signal: RecoverySignal): Boolean =
        signal != RecoverySignal.RESTING_HEART_RATE

    private fun valueOf(observation: DailyRecoveryObservation, signal: RecoverySignal): Double? =
        when (signal) {
            RecoverySignal.HRV -> observation.hrvRmssdMs
            RecoverySignal.RESTING_HEART_RATE -> observation.restingHeartRateBpm?.toDouble()
            RecoverySignal.SLEEP_DURATION -> observation.sleepDurationMinutes?.toDouble()
            RecoverySignal.SLEEP_QUALITY -> observation.sleepQualityScore
        }

    /**
     * Assess [today] against the baseline formed by the observations in the
     * [BASELINE_WINDOW_DAYS] days before it. Observations dated today are the
     * reading being judged (the latest one wins if duplicated); future-dated
     * observations are ignored. Dates are parameters — core stays clock-free.
     */
    fun assess(observations: List<DailyRecoveryObservation>, today: LocalDate): ReadinessAssessment {
        val windowStart = today.minusDays(BASELINE_WINDOW_DAYS.toLong())
        val baseline = observations.filter { !it.date.isBefore(windowStart) && it.date.isBefore(today) }
        val baselineDaysUsed = baseline.map { it.date }.distinct().size
        val todays = observations.filter { it.date == today }.lastOrNull()

        if (todays == null) {
            return insufficient(
                date = today,
                baselineDaysUsed = baselineDaysUsed,
                missing = RecoverySignal.entries,
                headline = "No recovery data recorded for today yet — readiness can't be scored.",
            )
        }

        val raw = RecoverySignal.entries.mapNotNull { signal ->
            val todayValue = valueOf(todays, signal) ?: return@mapNotNull null
            val baselineValues = baseline.mapNotNull { valueOf(it, signal) }
            if (baselineValues.size < MIN_BASELINE_OBSERVATIONS) return@mapNotNull null
            val mean = baselineValues.average()
            val sd = maxOf(sampleSd(baselineValues, mean), SD_FLOORS.getValue(signal))
            val signedZ = (todayValue - mean) / sd * if (higherIsBetter(signal)) 1.0 else -1.0
            Triple(
                signal,
                SignalAssessment(
                    signal = signal,
                    todayValue = todayValue,
                    baselineMean = mean,
                    baselineSd = sd,
                    baselineCount = baselineValues.size,
                    favorabilityZ = signedZ.coerceIn(-Z_CLAMP, Z_CLAMP),
                    weight = 0.0, // filled in after renormalisation below
                    contribution = 0.0,
                ),
                baselineValues.size,
            )
        }

        if (raw.isEmpty()) {
            return insufficient(
                date = today,
                baselineDaysUsed = baselineDaysUsed,
                missing = RecoverySignal.entries,
                headline = "Not enough baseline yet — readiness needs about " +
                    "$MIN_BASELINE_OBSERVATIONS days of readings per signal before it can be scored.",
            )
        }

        val totalWeight = raw.sumOf { SIGNAL_WEIGHTS.getValue(it.first) }
        val signals = raw.map { (signal, assessment, _) ->
            val weight = SIGNAL_WEIGHTS.getValue(signal) / totalWeight
            assessment.copy(weight = weight, contribution = assessment.favorabilityZ * weight)
        }.sortedByDescending { abs(it.contribution) }

        val compositeZ = signals.sumOf { it.contribution }
        val band = when {
            compositeZ >= PRIMED_Z -> ReadinessBand.PRIMED
            compositeZ >= STRAINED_Z -> ReadinessBand.NORMAL
            compositeZ >= DEPLETED_Z -> ReadinessBand.STRAINED
            else -> ReadinessBand.DEPLETED
        }
        // Display mapping only: z = 0 → 50, each SD is 25 points, clamped to
        // 0–100. Rounded, not truncated — floating-point dust below an exact
        // value must not shave a point off the displayed score.
        val score = (50.0 + 25.0 * compositeZ).coerceIn(0.0, 100.0).roundToInt()
        val missing = RecoverySignal.entries.filter { s -> signals.none { it.signal == s } }

        return ReadinessAssessment(
            date = today,
            status = ReadinessStatus.SCORED,
            band = band,
            score = score,
            compositeZ = compositeZ,
            signals = signals,
            missingSignals = missing,
            baselineDaysUsed = baselineDaysUsed,
            headline = headline(band, signals.first()),
        )
    }

    private fun insufficient(
        date: LocalDate,
        baselineDaysUsed: Int,
        missing: List<RecoverySignal>,
        headline: String,
    ) = ReadinessAssessment(
        date = date,
        status = ReadinessStatus.INSUFFICIENT_DATA,
        band = null,
        score = null,
        compositeZ = null,
        signals = emptyList(),
        missingSignals = missing,
        baselineDaysUsed = baselineDaysUsed,
        headline = headline,
    )

    private fun headline(band: ReadinessBand, top: SignalAssessment): String {
        val driver = when (top.signal) {
            RecoverySignal.HRV -> "HRV"
            RecoverySignal.RESTING_HEART_RATE -> "resting heart rate"
            RecoverySignal.SLEEP_DURATION -> "sleep duration"
            RecoverySignal.SLEEP_QUALITY -> "sleep quality"
        }
        val direction = if (top.favorabilityZ >= 0) "above" else "below"
        val summary = when (band) {
            ReadinessBand.PRIMED -> "Primed — readiness is above your baseline"
            ReadinessBand.NORMAL -> "Normal readiness — in line with your baseline"
            ReadinessBand.STRAINED -> "Strained — readiness is below your baseline"
            ReadinessBand.DEPLETED -> "Depleted — readiness is well below your baseline"
        }
        return "$summary; the biggest driver is $driver, ${formatZ(top.favorabilityZ)} SD $direction yours."
    }

    private fun formatZ(z: Double): String = "%.1f".format(abs(z))

    private fun sampleSd(values: List<Double>, mean: Double): Double {
        if (values.size < 2) return 0.0
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
    }
}
