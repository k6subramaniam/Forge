package com.forge.core.nutrition

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** The meal a [FoodLogEntry] belongs to — the daily view groups by meal (blueprint 3.7). */
enum class MealType {
    BREAKFAST,
    LUNCH,
    DINNER,
    SNACK,
}

/**
 * Where a logged food's nutrient profile came from (blueprint 3.7). The source
 * rides on every entry and is summarised onto every day because completeness
 * differs by source — most importantly [MYFITNESSPAL], whose Health Connect
 * feed carries meal-level macro totals only, never per-food micronutrients.
 */
enum class FoodSource {
    /** Typed in by hand (quick-add, favourites, custom foods). */
    MANUAL,

    /** USDA FoodData Central — whole foods, the most complete free profiles (blueprint 3.7). */
    USDA,

    /** Open Food Facts — packaged and barcode items (blueprint 3.7). */
    OPEN_FOOD_FACTS,

    /**
     * MyFitnessPal via Health Connect: meal-level totals only — calories,
     * macros, fibre, sugar, sometimes sodium (blueprint 3.7) — and no
     * micronutrient detail. Importers model a meal total as a single entry
     * whose per-100 g profile holds the totals and whose quantity is 100 g, so
     * the scaling math reproduces the totals exactly.
     */
    MYFITNESSPAL,

    /**
     * AI photo review (blueprint 3.7): identified foods with estimated
     * portions. Always an estimate — entries carry an uncertainty fraction and
     * are labelled as estimates, never presented as exact.
     */
    PHOTO_ESTIMATE,

    /** A packaged food's nutrition label read directly — exact values, not estimates (blueprint 3.7). */
    LABEL_SCAN,
}

/**
 * A set of nutrient amounts with one hard rule (blueprint 3.7): **null means
 * unknown**. Sources differ in completeness, and a missing value is shown as
 * missing — never zero-filled or invented. Zero is a real measurement ("this
 * food has no sugar"); null is "the source didn't say".
 *
 * The same shape serves two roles: as a food's profile (amounts per 100 g)
 * and, after [scaledTo], as the absolute amounts in a logged portion or a
 * total. Sodium is in mg (the USDA/Open Food Facts convention); vitamins A
 * and D in µg (RAE for A); omega-3 in g (see [Micronutrient] for which form
 * the reference intake covers).
 */
data class NutrientValues(
    val caloriesKcal: Double? = null,
    val proteinGrams: Double? = null,
    val carbsGrams: Double? = null,
    val fatGrams: Double? = null,
    val fibreGrams: Double? = null,
    val sugarGrams: Double? = null,
    val sodiumMg: Double? = null,
    val vitaminAMicrogramsRae: Double? = null,
    val vitaminCMg: Double? = null,
    val vitaminDMicrograms: Double? = null,
    val vitaminB12Micrograms: Double? = null,
    val ironMg: Double? = null,
    val calciumMg: Double? = null,
    val potassiumMg: Double? = null,
    val magnesiumMg: Double? = null,
    val zincMg: Double? = null,
    val omega3Grams: Double? = null,
) {
    init {
        val all = listOf(
            caloriesKcal, proteinGrams, carbsGrams, fatGrams, fibreGrams, sugarGrams,
            sodiumMg, vitaminAMicrogramsRae, vitaminCMg, vitaminDMicrograms,
            vitaminB12Micrograms, ironMg, calciumMg, potassiumMg, magnesiumMg,
            zincMg, omega3Grams,
        )
        require(all.all { it == null || it >= 0.0 }) { "nutrient values cannot be negative" }
    }

    /** This profile scaled from per-100 g to a [grams] portion; nulls stay null. */
    fun scaledTo(grams: Double): NutrientValues {
        require(grams > 0.0) { "grams must be positive" }
        val f = grams / 100.0
        return NutrientValues(
            caloriesKcal = caloriesKcal?.times(f),
            proteinGrams = proteinGrams?.times(f),
            carbsGrams = carbsGrams?.times(f),
            fatGrams = fatGrams?.times(f),
            fibreGrams = fibreGrams?.times(f),
            sugarGrams = sugarGrams?.times(f),
            sodiumMg = sodiumMg?.times(f),
            vitaminAMicrogramsRae = vitaminAMicrogramsRae?.times(f),
            vitaminCMg = vitaminCMg?.times(f),
            vitaminDMicrograms = vitaminDMicrograms?.times(f),
            vitaminB12Micrograms = vitaminB12Micrograms?.times(f),
            ironMg = ironMg?.times(f),
            calciumMg = calciumMg?.times(f),
            potassiumMg = potassiumMg?.times(f),
            magnesiumMg = magnesiumMg?.times(f),
            zincMg = zincMg?.times(f),
            omega3Grams = omega3Grams?.times(f),
        )
    }
}

/**
 * One logged food (blueprint 3.7): what was eaten, in which meal, from which
 * source, and the per-100 g profile it was logged against. The entry's own
 * amounts are always derived by scaling — there is no second, hand-entered
 * copy of the numbers to disagree with the profile.
 */
data class FoodLogEntry(
    val id: String,
    val timestamp: Instant,
    val meal: MealType,
    val name: String,
    val source: FoodSource,
    val quantityGrams: Double,
    /** Human portion as logged, e.g. "1 cup cooked" — display only; the math uses grams. */
    val portionDescription: String? = null,
    /** Nutrient profile per 100 g; scaled by [quantityGrams] for the entry's amounts. */
    val nutrientsPer100g: NutrientValues,
    /**
     * ± fraction on this entry's values, for estimates (blueprint 3.7: photo
     * analysis is typically within ~20–30 % on calories, so a photo entry
     * carries ~0.25). Null for measured, database, and label values.
     */
    val uncertaintyFraction: Double? = null,
) {
    init {
        require(quantityGrams > 0.0) { "quantityGrams must be positive" }
        require(uncertaintyFraction == null || uncertaintyFraction in 0.0..1.0) {
            "uncertaintyFraction must be a fraction in 0..1"
        }
    }

    /** True when the numbers are estimates and must be labelled as such (blueprint 3.7). */
    val isEstimate: Boolean get() = source == FoodSource.PHOTO_ESTIMATE

    /** Absolute nutrient amounts in the logged portion. */
    val nutrients: NutrientValues get() = nutrientsPer100g.scaledTo(quantityGrams)
}

/**
 * One nutrient's total over a set of entries. A total is only as complete as
 * its inputs: [value] sums what entries actually reported and stays null when
 * none did — a day where nobody reported fibre has an *unknown* fibre total,
 * not a zero one (blueprint 3.7).
 */
data class NutrientTotal(
    /** Sum of the reported values; null when no entry reported this nutrient. */
    val value: Double?,
    /** How many of the entries reported this nutrient. */
    val entriesReporting: Int,
    val entriesTotal: Int,
) {
    /** Every entry reported it — the total is exact for the logged day. */
    val isComplete: Boolean get() = entriesTotal > 0 && entriesReporting == entriesTotal

    /** Some entries reported it — the total is a lower bound. */
    val isPartial: Boolean get() = entriesReporting > 0 && entriesReporting < entriesTotal

    /** No entry reported it — nothing is known about this nutrient. */
    val isUnknown: Boolean get() = entriesReporting == 0
}

/** The energy/macro totals of a meal or a day (blueprint 3.7's daily view). */
data class MacroTotals(
    val calories: NutrientTotal,
    val protein: NutrientTotal,
    val carbs: NutrientTotal,
    val fat: NutrientTotal,
    val fibre: NutrientTotal,
    val sugar: NutrientTotal,
    val sodium: NutrientTotal,
) {
    companion object {
        /** Totals over [entries], each nutrient summed from the entries that report it. */
        fun from(entries: List<FoodLogEntry>): MacroTotals {
            fun total(select: (NutrientValues) -> Double?): NutrientTotal {
                var sum = 0.0
                var reporting = 0
                for (entry in entries) {
                    val v = select(entry.nutrients)
                    if (v != null) {
                        sum += v
                        reporting++
                    }
                }
                return NutrientTotal(
                    value = if (reporting > 0) sum else null,
                    entriesReporting = reporting,
                    entriesTotal = entries.size,
                )
            }
            return MacroTotals(
                calories = total { it.caloriesKcal },
                protein = total { it.proteinGrams },
                carbs = total { it.carbsGrams },
                fat = total { it.fatGrams },
                fibre = total { it.fibreGrams },
                sugar = total { it.sugarGrams },
                sodium = total { it.sodiumMg },
            )
        }
    }
}

/**
 * One day's food log, aggregated (blueprint 3.7): macro totals with their
 * completeness, per-meal subtotals, and the day's sources — days are
 * source-labelled because a MyFitnessPal-sourced day legitimately knows less
 * than a natively logged one.
 */
data class DailyTotals(
    val date: LocalDate,
    /** The day's entries, in logging order. */
    val entries: List<FoodLogEntry>,
    val macros: MacroTotals,
    /** Subtotals for the meals that have entries, in meal order. */
    val mealTotals: Map<MealType, MacroTotals>,
    /** Every source that contributed to the day (blueprint 3.7: source-labelled days). */
    val sources: Set<FoodSource>,
) {
    val entryCount: Int get() = entries.size

    /** True when any entry is an estimate — the day's numbers inherit the label. */
    val hasEstimates: Boolean get() = entries.any { it.isEstimate }

    /**
     * The day as slice 4's intake record for the adaptive-TDEE engine. Null
     * when no entry reported calories at all: an unknown-intake day must not
     * masquerade as a 0 kcal day in the back-calculation. A partial calorie
     * total still produces a day, carrying the known sum — [AdaptiveTdee]'s
     * contract is that callers pass honestly logged days.
     */
    fun toIntakeDay(): IntakeDay? =
        macros.calories.value?.let { IntakeDay(date, it.roundToInt()) }
}

/** One targeted nutrient compared with its target (blueprint 3.7 daily view: remaining budget). */
data class NutrientComparison(
    /** Known consumed amount (null: no entry reported it). */
    val consumed: Double?,
    val target: Int,
    /** Target − consumed when under target; 0 when over; null when consumed is unknown. */
    val remaining: Double?,
    /** Consumed − target when over target; 0 when under; null when consumed is unknown. */
    val overBy: Double?,
    /**
     * False when the consumed total is partial (some entries didn't report
     * the nutrient) — read the comparison as a lower bound, not an exact one.
     */
    val isComplete: Boolean,
)

/** A day's totals against the applicable [MacroTargets] (blueprint 3.7). */
data class MacroComparison(
    val targets: MacroTargets,
    val calories: NutrientComparison,
    val protein: NutrientComparison,
    val fat: NutrientComparison,
    val carbs: NutrientComparison,
    val fibre: NutrientComparison,
)

/**
 * Diet-log aggregation and target comparison (blueprint 3.7). Pure functions
 * over logged entries; dates and zones are parameters, the HistoryReadModels
 * convention — `:core` stays clock-free.
 */
object DietLog {

    /** The entries falling on [date] in [zone], in logging order. */
    fun entriesOn(entries: List<FoodLogEntry>, date: LocalDate, zone: ZoneId): List<FoodLogEntry> =
        entries
            .filter { it.timestamp.atZone(zone).toLocalDate() == date }
            .sortedBy { it.timestamp }

    /** Aggregate one day: totals, completeness, per-meal subtotals, sources. */
    fun summarise(entries: List<FoodLogEntry>, date: LocalDate, zone: ZoneId): DailyTotals {
        val day = entriesOn(entries, date, zone)
        val mealTotals = MealType.entries.mapNotNull { meal ->
            val mealEntries = day.filter { it.meal == meal }
            if (mealEntries.isEmpty()) null else meal to MacroTotals.from(mealEntries)
        }.toMap()
        return DailyTotals(
            date = date,
            entries = day,
            macros = MacroTotals.from(day),
            mealTotals = mealTotals,
            sources = day.map { it.source }.toSet(),
        )
    }

    /** Micronutrient coverage for a summarised day — see [MicronutrientCoverage]. */
    fun microCoverage(totals: DailyTotals): List<MicroCoverage> =
        MicronutrientCoverage.assessDay(totals.entries)

    /**
     * Compare a day with its targets: remaining or over amounts for calories
     * and each targeted macro. (Sugar and sodium are tracked in the totals
     * but carry no [MacroTargets] target, so they are not compared.)
     */
    fun compare(totals: DailyTotals, targets: MacroTargets): MacroComparison {
        fun cmp(total: NutrientTotal, target: Int): NutrientComparison {
            val consumed = total.value
            return NutrientComparison(
                consumed = consumed,
                target = target,
                remaining = consumed?.let { maxOf(0.0, target - it) },
                overBy = consumed?.let { maxOf(0.0, it - target) },
                isComplete = total.isComplete,
            )
        }
        return MacroComparison(
            targets = targets,
            calories = cmp(totals.macros.calories, targets.caloriesKcal),
            protein = cmp(totals.macros.protein, targets.proteinGrams),
            fat = cmp(totals.macros.fat, targets.fatGrams),
            carbs = cmp(totals.macros.carbs, targets.carbGrams),
            fibre = cmp(totals.macros.fibre, targets.fibreGrams),
        )
    }

    /**
     * Compare against a slice-4 [EnergyPlan], with [trainingDay] choosing
     * which of the plan's day targets applies (blueprint 3.7: training days
     * and rest days carry different targets).
     */
    fun compare(totals: DailyTotals, plan: EnergyPlan, trainingDay: Boolean): MacroComparison =
        compare(totals, if (trainingDay) plan.trainingDay else plan.restDay)
}
