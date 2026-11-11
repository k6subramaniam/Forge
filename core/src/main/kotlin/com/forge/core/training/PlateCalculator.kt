package com.forge.core.training

/**
 * Plate calculator (blueprint 3.1): given a target total weight and a bar weight,
 * work out the plates to load per side from the gym's kg plate set.
 *
 * Loading is greedy, heaviest plate first — which is also how a human loads a bar.
 * All plate denominations are exact binary fractions/integers, so Double math here
 * is exact; no rounding fudge is needed.
 */
object PlateCalculator {

    /** Available plate denominations in kg, heaviest first (blueprint 3.1). */
    val PLATES_KG: List<Double> = listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25)

    const val DEFAULT_BAR_KG = 20.0

    data class PlateLoad(
        val targetWeightKg: Double,
        val barWeightKg: Double,
        /** Plates for ONE side of the bar, heaviest first. */
        val platesPerSide: List<Double>,
        /** The weight actually loaded (bar + both sides). */
        val achievedWeightKg: Double,
        /** target − achieved. Positive when the target can't be hit exactly with this plate set. */
        val shortfallKg: Double,
    ) {
        val isExact: Boolean get() = shortfallKg == 0.0
    }

    fun calculate(targetWeightKg: Double, barWeightKg: Double = DEFAULT_BAR_KG): PlateLoad {
        require(targetWeightKg > 0) { "Target weight must be positive, was $targetWeightKg" }
        require(barWeightKg >= 0) { "Bar weight cannot be negative, was $barWeightKg" }

        if (targetWeightKg <= barWeightKg) {
            // Empty bar is the closest loadable weight.
            return PlateLoad(
                targetWeightKg = targetWeightKg,
                barWeightKg = barWeightKg,
                platesPerSide = emptyList(),
                achievedWeightKg = barWeightKg,
                shortfallKg = targetWeightKg - barWeightKg,
            )
        }

        var remainingPerSide = (targetWeightKg - barWeightKg) / 2.0
        val plates = mutableListOf<Double>()
        for (plate in PLATES_KG) {
            while (remainingPerSide >= plate) {
                plates += plate
                remainingPerSide -= plate
            }
        }

        val achieved = barWeightKg + 2.0 * plates.sum()
        return PlateLoad(
            targetWeightKg = targetWeightKg,
            barWeightKg = barWeightKg,
            platesPerSide = plates,
            achievedWeightKg = achieved,
            shortfallKg = targetWeightKg - achieved,
        )
    }
}
