package com.forge.core.training

/**
 * Plate calculator (blueprint 3.1): given a target total weight and a bar weight,
 * work out the plates to load per side from the gym's plate set for the active
 * unit system (decided 2026-11-11: lb set 45/35/25/10/5/2.5/1.25, kg set
 * 25/20/15/10/5/2.5/1.25).
 *
 * Loading is greedy, heaviest plate first — which is also how a human loads a bar.
 * All plate denominations are exact binary fractions/integers, so Double math here
 * is exact; no rounding fudge is needed.
 */
object PlateCalculator {

    /** Available plate denominations in kg, heaviest first (blueprint 3.1). */
    val PLATES_KG: List<Double> = listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25)

    /** Available plate denominations in lb, heaviest first (decided 2026-11-11). */
    val PLATES_LB: List<Double> = listOf(45.0, 35.0, 25.0, 10.0, 5.0, 2.5, 1.25)

    const val DEFAULT_BAR_KG = 20.0
    const val DEFAULT_BAR_LB = 45.0

    fun platesFor(unit: WeightUnit): List<Double> = when (unit) {
        WeightUnit.KG -> PLATES_KG
        WeightUnit.LB -> PLATES_LB
    }

    fun defaultBarWeight(unit: WeightUnit): Double = when (unit) {
        WeightUnit.KG -> DEFAULT_BAR_KG
        WeightUnit.LB -> DEFAULT_BAR_LB
    }

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

    /** A plate load expressed in [unit] — the unit-aware counterpart of [PlateLoad]. */
    data class UnitPlateLoad(
        val unit: WeightUnit,
        val targetWeight: Double,
        val barWeight: Double,
        /** Plates for ONE side of the bar, heaviest first, in [unit]. */
        val platesPerSide: List<Double>,
        /** The weight actually loaded (bar + both sides), in [unit]. */
        val achievedWeight: Double,
        /** target − achieved, in [unit]. */
        val shortfall: Double,
    ) {
        val isExact: Boolean get() = shortfall == 0.0
    }

    fun calculate(targetWeightKg: Double, barWeightKg: Double = DEFAULT_BAR_KG): PlateLoad {
        val load = calculate(
            targetWeight = targetWeightKg,
            barWeight = barWeightKg,
            unit = WeightUnit.KG,
        )
        return PlateLoad(
            targetWeightKg = load.targetWeight,
            barWeightKg = load.barWeight,
            platesPerSide = load.platesPerSide,
            achievedWeightKg = load.achievedWeight,
            shortfallKg = load.shortfall,
        )
    }

    /**
     * Unit-aware calculation. [targetWeight] and [barWeight] are expressed in
     * [unit]; the caller converts to/from canonical kg at the display boundary
     * (see [WeightConverter]) — the calculator itself never mixes units.
     */
    fun calculate(
        targetWeight: Double,
        unit: WeightUnit,
        barWeight: Double = defaultBarWeight(unit),
    ): UnitPlateLoad {
        require(targetWeight > 0) { "Target weight must be positive, was $targetWeight" }
        require(barWeight >= 0) { "Bar weight cannot be negative, was $barWeight" }

        if (targetWeight <= barWeight) {
            // Empty bar is the closest loadable weight.
            return UnitPlateLoad(
                unit = unit,
                targetWeight = targetWeight,
                barWeight = barWeight,
                platesPerSide = emptyList(),
                achievedWeight = barWeight,
                shortfall = targetWeight - barWeight,
            )
        }

        var remainingPerSide = (targetWeight - barWeight) / 2.0
        val plates = mutableListOf<Double>()
        for (plate in platesFor(unit)) {
            while (remainingPerSide >= plate) {
                plates += plate
                remainingPerSide -= plate
            }
        }

        val achieved = barWeight + 2.0 * plates.sum()
        return UnitPlateLoad(
            unit = unit,
            targetWeight = targetWeight,
            barWeight = barWeight,
            platesPerSide = plates,
            achievedWeight = achieved,
            shortfall = targetWeight - achieved,
        )
    }
}
