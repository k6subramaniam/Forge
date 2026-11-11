package com.forge.core.bodycomp

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

enum class DexaBrand {
    HOLOGIC,
    GE_LUNAR,
    OTHER,
}

/** One physical scanner: results differ between brands by about 1–2 pp, so scanners are never mixed. */
data class DexaScanner(
    val brand: DexaBrand,
    /** Where the machine is, e.g. the clinic name. */
    val site: String,
)

/**
 * One DXA report. DXA reports three compartments: fat, lean soft tissue and
 * bone mineral (Prado 2025 vocabulary; "lean soft tissue" is DXA's own term
 * and is not renamed).
 */
data class DexaScan(
    val date: LocalDate,
    val scanner: DexaScanner,
    val fatPercent: Double,
    val fatMassKg: Double? = null,
    val leanSoftTissueKg: Double? = null,
    val boneMineralContentKg: Double? = null,
) {
    init {
        require(fatPercent > 0.0 && fatPercent < 100.0) { "fatPercent must be in (0, 100), was $fatPercent" }
    }

    /** The scan as a body-fat figure, with DXA's error against the four-compartment criterion. */
    fun asEstimate(): BodyFatEstimate =
        BodyFatEstimate(CompositionMethod.DXA, date, fatPercent, MethodAccuracy.DXA_CRITERION_SE_PP)
}

/** What one method learned from the DEXA anchors. */
data class MethodCalibration(
    val method: CompositionMethod,
    /** DEXA scans that had a reading of this method close enough to pair with. */
    val anchors: List<DexaScan>,
    /** Added to the method's reading to land on DEXA: the mean of (DEXA − method) over the anchors. */
    val offsetPp: Double,
    /**
     * The method's error after calibration, measured from the anchors. Null
     * while there are too few anchors to measure it: the offset still applies,
     * but the uncalibrated error is kept.
     */
    val calibratedStandardErrorPp: Double?,
) {
    val anchorCount: Int get() = anchors.size

    /**
     * How much of each anchor scan's own measurement error the offset carries.
     * The offset is the plain mean over anchors, so each scan's error enters it
     * with weight 1/n. Every method calibrated on the same scan inherits the
     * same error, so [CompositionEnsemble] keeps it as a shared term when fusing.
     */
    val anchorErrorLoadings: Map<DexaScan, Double>
        get() = anchors.groupingBy { it }.eachCount().mapValues { (_, count) -> count.toDouble() / anchors.size }
}

/** A person's calibration: per-method corrections learned on one DEXA machine. */
data class PersonalCalibration(
    /** The machine the anchors came from, or null with no scans. */
    val scanner: DexaScanner?,
    val methods: Map<CompositionMethod, MethodCalibration>,
    /** Scans from other machines, which aren't mixed into the anchors. */
    val setAsideScans: List<DexaScan>,
) {
    /** [estimate] with its method's correction applied, or unchanged when the method has none. */
    fun apply(estimate: BodyFatEstimate): BodyFatEstimate {
        val calibration = methods[estimate.method] ?: return estimate
        val corrected = (estimate.fatPercent + calibration.offsetPp).coerceIn(MIN_FAT_PERCENT, MAX_FAT_PERCENT)
        return estimate.copy(
            fatPercent = corrected,
            standardErrorPp = calibration.calibratedStandardErrorPp ?: estimate.standardErrorPp,
            calibrated = true,
        )
    }

    companion object {
        val NONE = PersonalCalibration(scanner = null, methods = emptyMap(), setAsideScans = emptyList())

        // An offset can't move a reading out of the physically possible range.
        private const val MIN_FAT_PERCENT = 0.1
        private const val MAX_FAT_PERCENT = 99.9
    }
}

/**
 * Personal DEXA calibration (blueprint 3.14; IronLog-vs-DEXA comparison:
 * "DEXA is the anchor — 2–4 scans a year keep IronLog honest through
 * personal calibration").
 *
 * Each home method has a bias that differs from person to person but holds
 * steady within a person: the n-of-1 doctrine of Evidence Base v3 §C8. Pair
 * each DEXA scan with the method's readings near it, and the mean gap is
 * that bias for this person.
 *
 * - Readings within [PAIRING_WINDOW_DAYS] of a scan pair with it. The offset
 *   is the mean over anchors of each anchor's mean gap, so one noisy reading
 *   can't set it and a scan with many readings doesn't outweigh the others.
 * - Anchors come only from the machine of the latest scan. Brands differ by
 *   about 1–2 pp, so "always rescan on the same machine". Scans from other
 *   machines are set aside, not mixed.
 * - One anchor corrects the bias. From [MIN_ANCHORS_TO_NARROW] anchors the
 *   remaining error is measured, and the interval narrows to match.
 *   The calibrated error is the error of ONE new reading, because that is what
 *   it is applied to. It has two parts, added in quadrature:
 *   - the reading's own scatter: the variance of every individual paired
 *     reading's gap around the offset. Scatter within a scan's window counts,
 *     not just between scans; averaging first would hide it.
 *   - the offset's uncertainty: the variance of the per-anchor mean gaps ÷ n
 *     anchors, which matches how the offset is formed (anchors weighted
 *     equally). Piling readings onto one scan therefore can't mask
 *     disagreement between scans.
 *   It is never below DXA's repeat precision: a reading can't agree with DXA
 *   more closely than DXA agrees with itself.
 */
object DexaCalibration {

    const val PAIRING_WINDOW_DAYS = 3L
    const val MIN_ANCHORS_TO_NARROW = 3

    /**
     * Learns per-method corrections from [scans] and uncalibrated [homeEstimates]
     * (BIA readings that passed [BiaProtocol] QC, tape and photo estimates).
     */
    fun calibrate(scans: List<DexaScan>, homeEstimates: List<BodyFatEstimate>): PersonalCalibration {
        require(homeEstimates.none { it.calibrated }) { "Calibrate from raw readings, not already-calibrated ones" }
        require(homeEstimates.all { it.method.isEnsembleMember }) { "Only home methods are calibrated against DEXA" }
        if (scans.isEmpty()) return PersonalCalibration.NONE

        val scanner = scans.maxBy { it.date }.scanner
        val (anchors, setAside) = scans.partition { it.scanner == scanner }

        val methods = homeEstimates.groupBy { it.method }.mapNotNull { (method, readings) ->
            // Per anchor: the gap (DEXA − reading) of every reading paired with it.
            val paired = anchors.mapNotNull { scan ->
                readings
                    .filter { abs(ChronoUnit.DAYS.between(scan.date, it.date)) <= PAIRING_WINDOW_DAYS }
                    .map { scan.fatPercent - it.fatPercent }
                    .ifEmpty { null }
                    ?.let { scan to it }
            }
            if (paired.isEmpty()) return@mapNotNull null
            val gapsByAnchor = paired.map { it.second }
            val n = gapsByAnchor.size
            val anchorMeans = gapsByAnchor.map { it.average() }
            val offset = anchorMeans.average()
            val calibratedSe = if (n >= MIN_ANCHORS_TO_NARROW) {
                val gaps = gapsByAnchor.flatten()
                val singleReadingVariance = gaps.sumOf { (it - offset) * (it - offset) } / (gaps.size - 1)
                val offsetVariance = anchorMeans.sumOf { (it - offset) * (it - offset) } / (n - 1) / n
                max(sqrt(singleReadingVariance + offsetVariance), MethodAccuracy.DXA_REPEAT_SE_PP)
            } else {
                null
            }
            method to MethodCalibration(method, paired.map { it.first }, offset, calibratedSe)
        }.toMap()

        return PersonalCalibration(scanner, methods, setAside)
    }
}
