package com.forge.core.bodycomp

import com.forge.core.model.Sex
import java.time.LocalDate
import kotlin.math.log10

/**
 * One tape session. Each circumference carries its anatomical site, because
 * each equation was derived on specific sites, and where the tape goes changes
 * the answer (Pawlak et al., Evidence Base v3 §A4). A site an equation was not
 * derived on is never substituted for one it was.
 */
data class TapeMeasurement(
    val date: LocalDate,
    /** Just below the larynx (Navy, both sexes). */
    val neckCm: Double? = null,
    /** Abdomen at the navel (Navy, men). */
    val waistAtNavelCm: Double? = null,
    /** Natural waist, at its narrowest (Navy, women). */
    val naturalWaistCm: Double? = null,
    /** Just above the iliac crest, the NHANES site RFM was derived on. */
    val waistAtIliacCrestCm: Double? = null,
    /** Hips at the widest point of the buttocks (Navy, women). */
    val hipCm: Double? = null,
)

/**
 * The tape members of the ensemble (Evidence Base v3 §A4: "the Navy/RFM
 * members"). Pure formulas over raw measures. Height enters as itself, never
 * through an index (no BMI — v3 §F).
 */
object TapeEquations {

    /**
     * US Navy circumference equation (Hodgdon & Beckett 1984), metric form:
     * - men: 495 / (1.0324 − 0.19077·log10(waist − neck) + 0.15456·log10(height)) − 450
     * - women: 495 / (1.29579 − 0.35004·log10(waist + hip − neck) + 0.22100·log10(height)) − 450
     *
     * [waistCm] is the navel abdomen for men and the natural waist for women,
     * and [hipCm] is required for women.
     */
    fun navyBodyFatPercent(
        sex: Sex,
        heightCm: Double,
        neckCm: Double,
        waistCm: Double,
        hipCm: Double? = null,
    ): Double {
        require(heightCm > 0.0 && neckCm > 0.0 && waistCm > 0.0) { "circumferences and height must be positive" }
        return when (sex) {
            Sex.MALE -> {
                require(waistCm > neckCm) { "Navy (men) needs waist > neck, was $waistCm vs $neckCm" }
                495.0 / (1.0324 - 0.19077 * log10(waistCm - neckCm) + 0.15456 * log10(heightCm)) - 450.0
            }
            Sex.FEMALE -> {
                requireNotNull(hipCm) { "Navy (women) needs a hip circumference" }
                require(hipCm > 0.0) { "hipCm must be positive" }
                require(waistCm + hipCm > neckCm) { "Navy (women) needs waist + hip > neck" }
                495.0 / (1.29579 - 0.35004 * log10(waistCm + hipCm - neckCm) + 0.22100 * log10(heightCm)) - 450.0
            }
        }
    }

    /**
     * Relative fat mass (Woolcott & Bergman 2018, Sci Rep 8:10980):
     * 64 − 20 × (height / waist), plus 12 for women. Height and waist in the
     * same unit; waist taken at the iliac crest, as in the NHANES data it was
     * derived on.
     */
    fun rfmBodyFatPercent(sex: Sex, heightCm: Double, waistAtIliacCrestCm: Double): Double {
        require(heightCm > 0.0 && waistAtIliacCrestCm > 0.0) { "height and waist must be positive" }
        val sexTerm = if (sex == Sex.FEMALE) 12.0 else 0.0
        return 64.0 - 20.0 * (heightCm / waistAtIliacCrestCm) + sexTerm
    }

    /**
     * Every tape estimate [measurement] supports: Navy when its sites for this
     * sex were taken, RFM when the iliac-crest waist was. A result outside
     * (0, 100) means the inputs are implausible for the equation, so it is
     * dropped rather than clamped into a fake reading.
     */
    fun estimates(
        measurement: TapeMeasurement,
        sex: Sex,
        heightCm: Double,
        accuracy: MethodAccuracy = MethodAccuracy.PROVISIONAL,
    ): List<BodyFatEstimate> = buildList {
        val neck = measurement.neckCm
        val navyWaist = when (sex) {
            Sex.MALE -> measurement.waistAtNavelCm
            Sex.FEMALE -> measurement.naturalWaistCm
        }
        val navyHipReady = sex == Sex.MALE || measurement.hipCm != null
        if (neck != null && navyWaist != null && navyHipReady) {
            val value = navyBodyFatPercent(sex, heightCm, neck, navyWaist, measurement.hipCm)
            addIfPlausible(CompositionMethod.NAVY_TAPE, measurement.date, value, accuracy)
        }
        measurement.waistAtIliacCrestCm?.let { waist ->
            val value = rfmBodyFatPercent(sex, heightCm, waist)
            addIfPlausible(CompositionMethod.RFM_TAPE, measurement.date, value, accuracy)
        }
    }

    private fun MutableList<BodyFatEstimate>.addIfPlausible(
        method: CompositionMethod,
        date: LocalDate,
        value: Double,
        accuracy: MethodAccuracy,
    ) {
        if (value > 0.0 && value < 100.0) {
            add(BodyFatEstimate(method, date, value, accuracy.of(method)))
        }
    }
}
