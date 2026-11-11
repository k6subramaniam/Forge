package com.forge.core.bodycomp

import java.time.LocalDate
import kotlin.math.sqrt

/** How much one member reading counted in a fused estimate. */
data class MemberContribution(
    val method: CompositionMethod,
    /** The reading after any personal calibration. */
    val fatPercent: Double,
    val standardErrorPp: Double,
    val calibrated: Boolean,
    /** This member's share of the fused value. Shares sum to 1. */
    val weightShare: Double,
)

/** The fused body-fat estimate for a day, with what went into it and its caveats. */
data class EnsembleEstimate(
    /** Method [CompositionMethod.ENSEMBLE], molecular level, with the fused standard error. */
    val estimate: BodyFatEstimate,
    val members: List<MemberContribution>,
    /** Fat mass and fat-free mass, when the day's body mass was supplied. */
    val split: FatMassSplit?,
    val caveats: List<CompositionCaveat>,
)

/**
 * The body-composition ensemble (blueprint 3.14/3.15): photo, watch BIA and
 * tape, fused by their accuracy, after any personal DEXA calibration.
 *
 * Fusion is inverse-variance weighting, which assumes members err
 * independently. That doesn't hold for two methods reading the same input:
 * the Navy and RFM equations both read the tape, so a misplaced tape moves
 * both. Members are therefore first collapsed per [InputSource], and a source
 * counts as one witness. Inside a source, readings are averaged by
 * inverse variance, but the source keeps its best member's error, with no
 * narrowing from correlated readings. Independent sources then fuse
 * normally. The interval only narrows on independent evidence (Evidence
 * Base v3 §A3: honest interval widths).
 */
object CompositionEnsemble {

    /**
     * Fuses [estimates] (home methods only, uncalibrated) into one figure for
     * [date]. Returns null with nothing to fuse. A DXA scan is the calibration
     * anchor, not a member: pass scans to [DexaCalibration] instead.
     */
    fun combine(
        date: LocalDate,
        estimates: List<BodyFatEstimate>,
        calibration: PersonalCalibration = PersonalCalibration.NONE,
        bodyMassKg: Double? = null,
    ): EnsembleEstimate? {
        require(estimates.all { it.method.isEnsembleMember }) {
            "Only home methods are ensemble members; DXA is the calibration anchor"
        }
        if (estimates.isEmpty()) return null

        val members = estimates.map(calibration::apply)

        // Collapse each input source to one witness.
        class Witness(val value: Double, val standardError: Double, val readings: List<Pair<BodyFatEstimate, Double>>)
        val witnesses = members.groupBy { it.method.source }.values.map { group ->
            val weights = group.map { 1.0 / (it.standardErrorPp * it.standardErrorPp) }
            val total = weights.sum()
            val value = group.zip(weights).sumOf { (e, w) -> e.fatPercent * w } / total
            Witness(
                value = value,
                standardError = group.minOf { it.standardErrorPp },
                readings = group.zip(weights.map { it / total }),
            )
        }

        // Fuse the independent witnesses by inverse variance.
        val witnessWeights = witnesses.map { 1.0 / (it.standardError * it.standardError) }
        val totalWeight = witnessWeights.sum()
        val fused = witnesses.zip(witnessWeights).sumOf { (w, weight) -> w.value * weight } / totalWeight
        val fusedSe = sqrt(1.0 / totalWeight)

        val contributions = witnesses.zip(witnessWeights).flatMap { (witness, weight) ->
            witness.readings.map { (member, shareInWitness) ->
                MemberContribution(
                    method = member.method,
                    fatPercent = member.fatPercent,
                    standardErrorPp = member.standardErrorPp,
                    calibrated = member.calibrated,
                    weightShare = weight / totalWeight * shareInWitness,
                )
            }
        }

        val caveats = buildList {
            if (members.any { it.method == CompositionMethod.WRIST_BIA }) {
                add(CompositionCaveat.BIA_HYDRATION)
                add(CompositionCaveat.WRIST_BIA_NOT_LAB_BIA)
            }
            if (members.any { !it.calibrated }) add(CompositionCaveat.UNCALIBRATED)
            if (witnesses.size == 1) add(CompositionCaveat.SINGLE_SOURCE)
        }

        val estimate = BodyFatEstimate(
            method = CompositionMethod.ENSEMBLE,
            date = date,
            fatPercent = fused,
            standardErrorPp = fusedSe,
            calibrated = members.all { it.calibrated },
        )
        return EnsembleEstimate(
            estimate = estimate,
            members = contributions,
            split = bodyMassKg?.let { FatMassSplit.of(it, fused) },
            caveats = caveats,
        )
    }
}
