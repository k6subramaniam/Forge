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
    val anchorCount: Int,
    /** Added to the method's reading to land on DEXA: the mean of (DEXA − method) over the anchors. */
    val offsetPp: Double,
    /**
     * The method's error after calibration, measured from the anchors. Null
     * while there are too few anchors to measure it: the offset still applies,
     * but the uncalibrated error is kept.
     */
    val calibratedStandardErrorPp: Double?,
)

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
 * - Readings within [PAIRING_WINDOW_DAYS] of a scan pair with it, averaged
 *   so one noisy reading can't set the offset.
 * - Anchors come only from the machine of the latest scan. Brands differ by
 *   about 1–2 pp, so "always rescan on the same machine". Scans from other
 *   machines are set aside, not mixed.
 * - One anchor corrects the bias. From [MIN_ANCHORS_TO_NARROW] anchors the
 *   remaining error is measured, and the interval narrows to match.
 *   The calibrated error is the anchors' residual SD × √(1 + 1/n), the
 *   prediction error for a new reading including the offset's own
 *   uncertainty. It is never below DXA's repeat precision: a reading can't
 *   agree with DXA more closely than DXA agrees with itself.
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
            val residuals = anchors.mapNotNull { scan ->
                val near = readings.filter { abs(ChronoUnit.DAYS.between(scan.date, it.date)) <= PAIRING_WINDOW_DAYS }
                if (near.isEmpty()) null else scan.fatPercent - near.map { it.fatPercent }.average()
            }
            if (residuals.isEmpty()) return@mapNotNull null
            val n = residuals.size
            val offset = residuals.average()
            val calibratedSe = if (n >= MIN_ANCHORS_TO_NARROW) {
                val variance = residuals.sumOf { (it - offset) * (it - offset) } / (n - 1)
                max(sqrt(variance) * sqrt(1.0 + 1.0 / n), MethodAccuracy.DXA_REPEAT_SE_PP)
            } else {
                null
            }
            method to MethodCalibration(method, n, offset, calibratedSe)
        }.toMap()

        return PersonalCalibration(scanner, methods, setAside)
    }
}
