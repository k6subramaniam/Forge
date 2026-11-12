package com.forge.core.nutrition

import com.forge.core.model.ActivityLevel
import com.forge.core.model.GoalProfile
import com.forge.core.model.GoalType
import com.forge.core.model.Sex
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One day's nutrition targets: calories plus macro split, in whole grams. */
data class MacroTargets(
    val caloriesKcal: Int,
    val proteinGrams: Int,
    val fatGrams: Int,
    val carbGrams: Int,
    val fibreGrams: Int,
    /**
     * True when protein was reduced below its g/kg dose so that protein and the
     * fat floor fit the calorie target. At very high bodyweight on a floored
     * target, the full dose plus fat can exceed the day's energy. The plan then
     * stays consistent and says so, rather than promising both.
     */
    val proteinCappedToFit: Boolean = false,
)

/**
 * The daily calorie target derived from a maintenance estimate and the goal
 * (blueprint 3.7/3.15).
 */
data class CalorieTarget(
    /** Maintenance the target was derived from (formula or adaptive), kcal/day. */
    val maintenanceKcal: Int,
    /** The target to eat at, after the goal adjustment and the BMR floor. */
    val caloriesKcal: Int,
    /** [caloriesKcal] minus [maintenanceKcal] — the surplus (+) or deficit (−). */
    val adjustmentKcal: Int,
    /** The goal's intended rate of weight change behind [adjustmentKcal]. */
    val targetRateKgPerWeek: Double,
    /** True when the BMR floor (blueprint 3.7) raised the target. */
    val floorApplied: Boolean,
)

/**
 * A full day-target plan (blueprint 3.7): the average day plus the training-day
 * and rest-day variants, whose weekly mean is the average day by construction.
 */
data class EnergyPlan(
    /** Formula BMR of the profile, kcal/day — also the calorie floor. */
    val bmrKcal: Int,
    val target: CalorieTarget,
    val averageDay: MacroTargets,
    val trainingDay: MacroTargets,
    val restDay: MacroTargets,
    /** True when the BMR floor clamped the rest day and the split rebalanced. */
    val daySplitFloorApplied: Boolean,
)

/**
 * Deterministic energy and macro targets (blueprint 3.7, evidence rules in
 * 3.15). No AI anywhere in this object: every number traces to the cited
 * formula or standard below, and the coach may only apply, never invent,
 * these targets (blueprint 3.15 grounding rule).
 *
 * No BMI is computed or used anywhere here — height and weight enter only as
 * the raw measures the formulas themselves need (Kumar's ruling, blueprint
 * 3.15 / Evidence Base v3 §F).
 */
object EnergyTargets {

    /**
     * Energy density of body-mass change: ~7,700 kcal per kg. The conventional
     * approximation behind the energy-balance identity blueprint 3.15's
     * adaptive TDEE uses (lineage: Wishnofsky 1958's 3,500 kcal/lb rule).
     */
    const val KCAL_PER_KG_BODY_MASS = 7_700.0

    /** Atwater energy factors (kcal per gram) for protein and carbohydrate. */
    const val KCAL_PER_G_PROTEIN = 4

    /** Atwater energy factor (kcal per gram) for fat. */
    const val KCAL_PER_G_FAT = 9

    /** Atwater energy factor (kcal per gram) for carbohydrate. */
    const val KCAL_PER_G_CARB = 4

    /** Share of daily energy assigned to fat before the per-kg floor (IOM AMDR mid-range). */
    const val FAT_CALORIE_SHARE = 0.25

    /** Minimum fat intake, g per kg of bodyweight, applied at low calorie targets. */
    const val FAT_FLOOR_GRAMS_PER_KG = 0.5

    /** IOM DRI fibre rule: grams of fibre per 1,000 kcal of target. */
    const val FIBRE_GRAMS_PER_1000_KCAL = 14.0

    /** Training days carry this much more than the average day (see [dailyTargets]). */
    const val TRAINING_DAY_BONUS = 0.05

    /**
     * Basal metabolic rate by Mifflin-St Jeor (Mifflin et al., Am J Clin Nutr
     * 1990) — the formula blueprint 3.7 names for maintenance and 3.15 keeps
     * as the starting prior:
     * men 10·kg + 6.25·cm − 5·age + 5; women the same with −161.
     */
    fun bmr(weightKg: Double, heightCm: Double, ageYears: Int, sex: Sex): Double {
        require(weightKg > 0.0) { "weightKg must be positive" }
        require(heightCm > 0.0) { "heightCm must be positive" }
        require(ageYears > 0) { "ageYears must be positive" }
        val base = 10.0 * weightKg + 6.25 * heightCm - 5.0 * ageYears
        return when (sex) {
            Sex.MALE -> base + 5.0
            Sex.FEMALE -> base - 161.0
        }
    }

    /** BMR of an onboarding [profile] — see the primitive overload. */
    fun bmr(profile: GoalProfile): Double =
        bmr(profile.weightKg, profile.heightCm, profile.ageYears, profile.sex)

    /**
     * The standard activity factors applied to Mifflin-St Jeor BMR (the
     * long-standing Harris-Benedict activity scale): sedentary 1.2, lightly
     * active 1.375, moderately active 1.55, very active 1.725, athlete 1.9.
     */
    fun activityMultiplier(level: ActivityLevel): Double = when (level) {
        ActivityLevel.SEDENTARY -> 1.2
        ActivityLevel.LIGHT -> 1.375
        ActivityLevel.MODERATE -> 1.55
        ActivityLevel.HIGH -> 1.725
        ActivityLevel.ATHLETE -> 1.9
    }

    /** Formula TDEE: BMR × activity factor. The prior the adaptive estimate (3.15) refines. */
    fun formulaTdee(
        weightKg: Double,
        heightCm: Double,
        ageYears: Int,
        sex: Sex,
        activityLevel: ActivityLevel,
    ): Double = bmr(weightKg, heightCm, ageYears, sex) * activityMultiplier(activityLevel)

    /** Formula TDEE of an onboarding [profile]. */
    fun formulaTdee(profile: GoalProfile): Double = formulaTdee(
        profile.weightKg, profile.heightCm, profile.ageYears, profile.sex, profile.activityLevel,
    )

    /**
     * The goal's intended rate of bodyweight change, as a fraction of current
     * bodyweight per week (blueprint 3.7: targets are set by goal, then
     * adjusted by results): a lean-gain surplus pace of +0.25 %/week and a
     * fat-loss pace of −0.5 %/week — the conservative ends of the commonly
     * recommended 0.25–0.5 % (gain) and 0.5–1 % (loss) weekly ranges, chosen
     * so the default never chases scale speed over adherence. Recomp and
     * maintain intend a stable scale by definition.
     */
    fun targetRateFractionPerWeek(goal: GoalType): Double = when (goal) {
        GoalType.BUILD_MUSCLE -> 0.0025
        GoalType.LOSE_FAT -> -0.005
        GoalType.RECOMP -> 0.0
        GoalType.MAINTAIN -> 0.0
    }

    /** [targetRateFractionPerWeek] expressed in kg/week at [weightKg]. */
    fun targetRateKgPerWeek(goal: GoalType, weightKg: Double): Double =
        targetRateFractionPerWeek(goal) * weightKg

    /**
     * Daily calorie target from a [maintenanceKcal] estimate: maintenance
     * plus the goal's energy adjustment — target rate (kg/week) × 7,700
     * kcal/kg ÷ 7 days. A deficit target is floored at BMR (rounded up):
     * eating below basal needs is never a sane default, and blueprint 3.7's
     * targets are starting points the weekly rule (see [AdaptiveTdee]) then
     * adjusts by results.
     */
    fun calorieTarget(profile: GoalProfile, maintenanceKcal: Double): CalorieTarget {
        require(maintenanceKcal > 0.0) { "maintenanceKcal must be positive" }
        val rateKgPerWeek = targetRateKgPerWeek(profile.goal, profile.weightKg)
        val adjustment = rateKgPerWeek * KCAL_PER_KG_BODY_MASS / 7.0
        val rawTarget = maintenanceKcal + adjustment
        val bmr = bmr(profile)
        val floorApplied = rawTarget < bmr
        val calories = if (floorApplied) ceil(bmr).toInt() else rawTarget.roundToInt()
        val maintenance = maintenanceKcal.roundToInt()
        return CalorieTarget(
            maintenanceKcal = maintenance,
            caloriesKcal = calories,
            adjustmentKcal = calories - maintenance,
            targetRateKgPerWeek = rateKgPerWeek,
            floorApplied = floorApplied,
        )
    }

    /**
     * Protein dose in g/kg of bodyweight, by goal. All picks sit inside
     * blueprint 3.7's 1.6–2.2 g/kg band, which rests on the ISSN position
     * stand (Jäger et al. 2017: 1.4–2.2 g/kg/day) and the Morton et al. 2018
     * dose-response meta-analysis (benefit plateaus near ~1.6 g/kg in a
     * surplus/maintenance context). Blueprint 3.15's rule — higher in a
     * deficit — puts fat loss at the top of the band; recomp, which asks for
     * muscle gain at maintenance calories, sits above plain maintenance.
     */
    fun proteinGramsPerKg(goal: GoalType): Double = when (goal) {
        GoalType.MAINTAIN -> 1.6
        GoalType.BUILD_MUSCLE -> 1.8
        GoalType.RECOMP -> 2.0
        GoalType.LOSE_FAT -> 2.2
    }

    /**
     * Macro split for one day at [caloriesKcal] (blueprint 3.7):
     * protein from [proteinGramsPerKg] × bodyweight; fat at 25 % of calories
     * (mid-range of the IOM DRI acceptable distribution, 20–35 % of energy)
     * floored at 0.5 g/kg so very low targets never crush fat intake;
     * carbohydrate takes the remainder. Fibre follows the IOM DRI rule of
     * 14 g per 1,000 kcal. Protein and fat are rounded first and carbs absorb
     * the rounding, so the split always sums back to the calorie target
     * (±2 kcal of carb rounding).
     *
     * Feasibility: when the protein dose plus fat would exceed the calories,
     * protein is capped to what the calories allow after fat, carbs are 0, and
     * [MacroTargets.proteinCappedToFit] is set. Fat keeps its floor: it is the
     * smaller, essential allocation, and the cap only binds at very high
     * bodyweight on a floored target.
     */
    fun macroTargets(caloriesKcal: Int, weightKg: Double, goal: GoalType): MacroTargets {
        require(caloriesKcal > 0) { "caloriesKcal must be positive" }
        require(weightKg > 0.0) { "weightKg must be positive" }
        val dosedProteinGrams = (proteinGramsPerKg(goal) * weightKg).roundToInt()
        val fatGrams = max(
            FAT_CALORIE_SHARE * caloriesKcal / KCAL_PER_G_FAT,
            FAT_FLOOR_GRAMS_PER_KG * weightKg,
        ).roundToInt()
        val proteinGrams = min(dosedProteinGrams, proteinThatFits(caloriesKcal, fatGrams))
        return splitAround(caloriesKcal, proteinGrams, fatGrams, capped = proteinGrams < dosedProteinGrams)
    }

    /** The most protein (g) that fits [caloriesKcal] after [fatGrams] of fat, never negative. */
    private fun proteinThatFits(caloriesKcal: Int, fatGrams: Int): Int =
        max(0, (caloriesKcal - fatGrams * KCAL_PER_G_FAT) / KCAL_PER_G_PROTEIN)

    /**
     * The full target plan for [profile] at a [maintenanceKcal] estimate
     * (formula TDEE, or the adaptive estimate once it exists — blueprint
     * 3.15): the average day from [calorieTarget]/[macroTargets], plus
     * training-day and rest-day variants per blueprint 3.7 ("training days vs
     * rest days can carry different targets").
     *
     * Split rule: training days get +5 % over the average; rest days take
     * whatever keeps the week's total exactly the target × 7, so the split
     * never drifts the weekly energy the goal was set from. Protein and fat
     * grams are identical on both day types — the swing lands entirely on
     * carbohydrate, fuelling training days. If the rest day would fall below
     * the BMR floor it is clamped to the floor and the training day
     * rebalances instead ([EnergyPlan.daySplitFloorApplied]). If the protein
     * cap binds on any day (see [macroTargets]), the lowest day's cap applies
     * to all of them, so protein stays identical across day types.
     */
    fun dailyTargets(profile: GoalProfile, maintenanceKcal: Double): EnergyPlan {
        val target = calorieTarget(profile, maintenanceKcal)
        val average = macroTargets(target.caloriesKcal, profile.weightKg, profile.goal)
        val bmrKcal = ceil(bmr(profile)).toInt()
        val trainDays = profile.trainingDaysPerWeek
        val restDays = 7 - trainDays
        if (trainDays <= 0 || restDays <= 0) {
            return EnergyPlan(bmrKcal, target, average, average, average, daySplitFloorApplied = false)
        }
        val weeklyTotal = target.caloriesKcal * 7
        var trainingKcal = (target.caloriesKcal * (1.0 + TRAINING_DAY_BONUS)).roundToInt()
        var restKcal = ((weeklyTotal - trainDays * trainingKcal).toDouble() / restDays).roundToInt()
        var splitFloorApplied = false
        if (restKcal < bmrKcal) {
            restKcal = bmrKcal
            trainingKcal = ((weeklyTotal - restDays * restKcal).toDouble() / trainDays).roundToInt()
            splitFloorApplied = true
        }
        val dosedProteinGrams = (proteinGramsPerKg(profile.goal) * profile.weightKg).roundToInt()
        val proteinGrams = listOf(target.caloriesKcal, trainingKcal, restKcal)
            .minOf { proteinThatFits(it, average.fatGrams) }
            .coerceAtMost(dosedProteinGrams)
        val capped = proteinGrams < dosedProteinGrams
        return EnergyPlan(
            bmrKcal = bmrKcal,
            target = target,
            averageDay = splitAround(target.caloriesKcal, proteinGrams, average.fatGrams, capped),
            trainingDay = splitAround(trainingKcal, proteinGrams, average.fatGrams, capped),
            restDay = splitAround(restKcal, proteinGrams, average.fatGrams, capped),
            daySplitFloorApplied = splitFloorApplied,
        )
    }

    /**
     * Carbs and fibre around fixed protein/fat grams: carbs take whatever
     * energy remains (never negative — at extreme targets they floor at 0
     * rather than reporting a nonsense negative), fibre scales with the day.
     */
    private fun splitAround(
        caloriesKcal: Int,
        proteinGrams: Int,
        fatGrams: Int,
        capped: Boolean = false,
    ): MacroTargets {
        val remaining = caloriesKcal -
            proteinGrams * KCAL_PER_G_PROTEIN -
            fatGrams * KCAL_PER_G_FAT
        return MacroTargets(
            caloriesKcal = caloriesKcal,
            proteinGrams = proteinGrams,
            fatGrams = fatGrams,
            carbGrams = max(0, (remaining.toDouble() / KCAL_PER_G_CARB).roundToInt()),
            fibreGrams = (caloriesKcal * FIBRE_GRAMS_PER_1000_KCAL / 1000.0).roundToInt(),
            proteinCappedToFit = capped,
        )
    }
}
