package com.forge.core.bodycomp

import java.time.LocalDate

/**
 * The five levels body composition is organised in (Prado et al. 2025, AJCN
 * 122:384 — Evidence Base v3 §A1). Quantities from different levels are never
 * interchanged or renamed into one another.
 */
enum class CompositionLevel {
    ATOMIC,
    MOLECULAR,
    CELLULAR,
    TISSUE_SYSTEM,
    WHOLE_BODY,
}

/**
 * Which inputs a method reads. Two methods that read the same physical input
 * share its measurement error, so the ensemble counts them as one witness
 * (see [CompositionEnsemble]).
 */
enum class InputSource {
    DXA_SCAN,
    BIOIMPEDANCE,
    TAPE,
    PHOTO,
    COMBINED,
}

/**
 * How a body-fat figure was obtained. Every estimate in Forge names its method
 * (Prado 2025, Evidence Base v3 §A1).
 */
enum class CompositionMethod(
    val displayName: String,
    val source: InputSource,
    /** True for the at-home methods the ensemble fuses; DXA is the anchor, not a member. */
    val isEnsembleMember: Boolean,
) {
    DXA("DXA scan", InputSource.DXA_SCAN, isEnsembleMember = false),
    WRIST_BIA("Watch bioimpedance", InputSource.BIOIMPEDANCE, isEnsembleMember = true),
    NAVY_TAPE("Navy tape equation", InputSource.TAPE, isEnsembleMember = true),
    RFM_TAPE("Relative fat mass (tape)", InputSource.TAPE, isEnsembleMember = true),
    PHOTO("Photo estimate", InputSource.PHOTO, isEnsembleMember = true),
    ENSEMBLE("Forge ensemble", InputSource.COMBINED, isEnsembleMember = false),
}

/**
 * One body-fat figure: fat mass as a percentage of body mass, with the method
 * that produced it and its standard error in percentage points.
 *
 * Vocabulary follows Prado 2025 (Evidence Base v3 §A1): fat mass (FM),
 * fat-free mass (FFM), and — for DXA only — lean soft tissue. The conflated
 * term "lean body mass" is never used.
 */
data class BodyFatEstimate(
    val method: CompositionMethod,
    val date: LocalDate,
    val fatPercent: Double,
    val standardErrorPp: Double,
    /** True once a personal DEXA calibration has been applied (see [DexaCalibration]). */
    val calibrated: Boolean = false,
) {
    init {
        require(fatPercent > 0.0 && fatPercent < 100.0) { "fatPercent must be in (0, 100), was $fatPercent" }
        require(standardErrorPp > 0.0) { "standardErrorPp must be positive, was $standardErrorPp" }
    }

    /** Fat mass as a fraction of body mass is a molecular-level quantity (Prado 2025). */
    val level: CompositionLevel get() = CompositionLevel.MOLECULAR

    /** The 95% interval, clamped to the physically possible 0–100%. */
    val interval95: ClosedFloatingPointRange<Double>
        get() = (fatPercent - Z_95 * standardErrorPp).coerceAtLeast(0.0)..
            (fatPercent + Z_95 * standardErrorPp).coerceAtMost(100.0)

    companion object {
        /** Two-sided 95% normal quantile. */
        const val Z_95 = 1.96
    }
}

/**
 * The two-compartment split at the molecular level: fat mass plus fat-free
 * mass equals body mass. Height and weight stay raw measures; no index is
 * derived from them (no BMI — Evidence Base v3 §F).
 */
data class FatMassSplit(
    val bodyMassKg: Double,
    val fatMassKg: Double,
    val fatFreeMassKg: Double,
) {
    companion object {
        fun of(bodyMassKg: Double, fatPercent: Double): FatMassSplit {
            require(bodyMassKg > 0.0) { "bodyMassKg must be positive" }
            require(fatPercent > 0.0 && fatPercent < 100.0) { "fatPercent must be in (0, 100)" }
            val fatMass = bodyMassKg * fatPercent / 100.0
            return FatMassSplit(bodyMassKg, fatMass, bodyMassKg - fatMass)
        }
    }
}

/**
 * Typical error of each method as one standard error, in percentage points of
 * body fat. The ensemble weights its members by these (inverse variance) —
 * "fused by published accuracy" (IronLog-vs-DEXA comparison).
 *
 * Where the figures come from:
 * - DXA: about ±2 pp against the four-compartment criterion, and about
 *   ±0.5–1 pp on repeat scans on the same machine (IronLog-vs-DEXA
 *   comparison, compiled from Evidence Base v1–v3). That sheet's ± figures are
 *   read as 95% half-widths, so SE = half-width / 1.96.
 * - The four home methods: PROVISIONAL. Evidence Base v3 and the comparison
 *   sheet give only the ensemble's overall uncalibrated band (about ±4–5 pp);
 *   the per-method figures are in Evidence Base v1/v2, which this slice was
 *   written without. The values below were chosen so that the full uncalibrated
 *   ensemble lands inside that band (±4.9 pp at 95%). Replace them with the
 *   v1/v2 figures when available; nothing else needs to change.
 */
data class MethodAccuracy(
    val standardErrorPp: Map<CompositionMethod, Double>,
) {
    fun of(method: CompositionMethod): Double =
        requireNotNull(standardErrorPp[method]) { "No accuracy figure for $method" }

    companion object {
        /** DXA against the four-compartment criterion: ±2 pp at 95%. */
        const val DXA_CRITERION_SE_PP = 2.0 / BodyFatEstimate.Z_95

        /** DXA repeat scans on the same machine: ±1 pp at 95%, the upper end of ±0.5–1 pp. */
        const val DXA_REPEAT_SE_PP = 1.0 / BodyFatEstimate.Z_95

        val PROVISIONAL = MethodAccuracy(
            mapOf(
                CompositionMethod.DXA to DXA_CRITERION_SE_PP,
                CompositionMethod.WRIST_BIA to 4.5,
                CompositionMethod.NAVY_TAPE to 4.0,
                CompositionMethod.RFM_TAPE to 4.0,
                CompositionMethod.PHOTO to 4.5,
            ),
        )
    }
}

/** A statement shown with an estimate so its limits are never hidden. */
enum class CompositionCaveat(val message: String) {
    /**
     * Total body water is about 70–75% of fat-free mass (Prado 2025), and BIA
     * reads water — so hydration moves the reading (Evidence Base v3 §A1/A2).
     */
    BIA_HYDRATION(
        "Bioimpedance reads body water, so hydration shifts it. Readings count only " +
            "when taken in the morning, fasted, after using the bathroom, at your usual hydration.",
    ),

    /** Campa 2021's athlete equations are for segmental lab BIA, not a wrist device (v3 §A2). */
    WRIST_BIA_NOT_LAB_BIA(
        "A watch measures bioimpedance at the wrist. It is not the segmental lab BIA " +
            "that athlete-specific equations were built on, so it is weighted below DXA-grade methods.",
    ),

    UNCALIBRATED(
        "Not yet calibrated to your own DEXA scans, so the range is wide. " +
            "A DEXA scan, repeated on the same machine, narrows it.",
    ),

    SINGLE_SOURCE(
        "Only one kind of measurement fed today's estimate, so it rests on that input alone.",
    ),
}
