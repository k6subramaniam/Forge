package com.forge.core.training

/**
 * Display units (decided 2026-11-11: lb is the default, kg one toggle away).
 *
 * The core always stores and computes weights in kg; conversion happens only
 * at the display boundary, so flipping units never drifts stored data.
 * Plate sets live in [PlateCalculator]; overload increments in [ProgressiveOverload].
 */
enum class WeightUnit {
    LB,
    KG,
}

object WeightConverter {

    /** Exact international pound definition. */
    const val KG_PER_LB = 0.45359237

    fun kgToLb(kg: Double): Double = kg / KG_PER_LB

    fun lbToKg(lb: Double): Double = lb * KG_PER_LB

    fun convert(weight: Double, from: WeightUnit, to: WeightUnit): Double = when {
        from == to -> weight
        from == WeightUnit.KG && to == WeightUnit.LB -> kgToLb(weight)
        else -> lbToKg(weight)
    }
}
