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
 * counts as one witness. Inside a source, readings are averaged with
 * inverse-variance weights w, and their errors are treated as fully
 * correlated, so the source's error is Σ wᵢ·SEᵢ: never narrower than its best
 * member, and wider when a worse member pulls the average. Independent
 * sources then fuse normally. The interval only narrows on independent
 * evidence (Evidence Base v3 §A3: honest interval widths).
 *
 * Personal calibration couples sources again. Every method calibrated on the
 * same DEXA scans inherits those scans' measurement error through its offset
 * ([MethodCalibration.anchorErrorLoadings]). So each witness's error splits
 * into that shared part and an independent remainder. Weights stay
 * inverse-variance, but the reported error is that weighted mean's true
 * error: independent parts shrink as sources are added, and the shared part
 * doesn't. No number of home methods can average away the anchors' own error.
 */
object CompositionEnsemble {

    /**
     * Fuses [estimates] (home methods only, uncalibrated) into one figure for
     * [date]. Only readings dated [date] are fused, so callers can pass a
     * multi-day list (e.g. all of [BiaProtocol.acceptedEstimates]) without
     * yesterday's reading shifting today's figure. Returns null when none fall
     * on that day. A DXA scan is the calibration anchor, not a member: pass
     * scans to [DexaCalibration] instead.
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
        val todays = estimates.filter { it.date == date }
        if (todays.isEmpty()) return null

        val members = todays.map(calibration::apply)
        // Each member's share of every anchor scan's error (none when uncalibrated).
        val loadings = todays.map { calibration.methods[it.method]?.anchorErrorLoadings.orEmpty() }

        // Collapse each input source to one witness.
        class Witness(
            val value: Double,
            val standardError: Double,
            val anchorLoadings: Map<DexaScan, Double>,
            val readings: List<Pair<BodyFatEstimate, Double>>,
        )
        val witnesses = members.zip(loadings).groupBy { it.first.method.source }.values.map { group ->
            val raw = group.map { (e, _) -> 1.0 / (e.standardErrorPp * e.standardErrorPp) }
            val total = raw.sum()
            val shares = raw.map { it / total }
            Witness(
                value = group.zip(shares).sumOf { (m, w) -> m.first.fatPercent * w },
                // Fully correlated errors add linearly: SE(Σ wᵢxᵢ) = Σ wᵢ·SEᵢ.
                standardError = group.zip(shares).sumOf { (m, w) -> m.first.standardErrorPp * w },
                anchorLoadings = weightedSum(group.zip(shares).map { (m, w) -> m.second to w }),
                readings = group.zip(shares).map { (m, w) -> m.first to w },
            )
        }

        // Fuse the witnesses by inverse variance.
        val witnessWeights = witnesses.map { 1.0 / (it.standardError * it.standardError) }
        val totalWeight = witnessWeights.sum()
        val fractions = witnessWeights.map { it / totalWeight }
        val fused = witnesses.zip(witnessWeights).sumOf { (w, weight) -> w.value * weight } / totalWeight
        // Var(Σ fᵢxᵢ) = Σ fᵢ²·independentᵢ + σ²_DXA·Σₖ(Σᵢ fᵢ·Lᵢₖ)². With no shared
        // anchors this is 1/Σw, the plain inverse-variance result.
        val independentVariance = witnesses.zip(fractions).sumOf { (w, f) ->
            f * f * (w.standardError * w.standardError - anchorErrorVariance(w.anchorLoadings)).coerceAtLeast(0.0)
        }
        val fusedLoadings = weightedSum(witnesses.zip(fractions).map { (w, f) -> w.anchorLoadings to f })
        val fusedSe = sqrt(independentVariance + anchorErrorVariance(fusedLoadings))

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

    /** Σ fᵢ·Lᵢ, scan by scan. */
    private fun weightedSum(terms: List<Pair<Map<DexaScan, Double>, Double>>): Map<DexaScan, Double> =
        buildMap {
            terms.forEach { (loadings, f) -> loadings.forEach { (scan, l) -> merge(scan, f * l, Double::plus) } }
        }

    /** The variance an anchor-error mix carries: each scan errs independently by DXA's repeat precision. */
    private fun anchorErrorVariance(loadings: Map<DexaScan, Double>): Double =
        MethodAccuracy.DXA_REPEAT_SE_PP * MethodAccuracy.DXA_REPEAT_SE_PP * loadings.values.sumOf { it * it }
}
