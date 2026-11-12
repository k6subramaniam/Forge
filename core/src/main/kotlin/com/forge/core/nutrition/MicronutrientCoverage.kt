package com.forge.core.nutrition

/**
 * The micronutrients Forge tracks (blueprint 3.7), each with its adult-male
 * DRI reference intake.
 *
 * **Source of the reference table:** the IOM Dietary Reference Intakes
 * (National Academies), as tabulated in the NIH Office of Dietary
 * Supplements' nutrient fact sheets — RDA unless noted. Potassium and
 * omega-3 are Adequate Intakes (no RDA exists); the omega-3 AI covers ALA
 * specifically, EPA/DHA having no DRI. Vitamin D is the 19–70 y RDA
 * (15 µg = 600 IU) and magnesium the 31+ y RDA. Female and other-age-band
 * references differ; selecting them from the profile is a later refinement.
 * Blueprint 3.15 is the governing rule: gaps are judged against DRI/EAR
 * reference values, never invented thresholds.
 */
enum class Micronutrient(
    val displayName: String,
    val unit: String,
    /** Adult-male DRI reference intake per day, in [unit]. */
    val referenceIntake: Double,
) {
    VITAMIN_A("Vitamin A", "µg RAE", 900.0),
    VITAMIN_C("Vitamin C", "mg", 90.0),
    VITAMIN_D("Vitamin D", "µg", 15.0),
    VITAMIN_B12("Vitamin B12", "µg", 2.4),
    IRON("Iron", "mg", 8.0),
    CALCIUM("Calcium", "mg", 1_000.0),
    POTASSIUM("Potassium", "mg", 3_400.0),
    MAGNESIUM("Magnesium", "mg", 420.0),
    ZINC("Zinc", "mg", 11.0),
    OMEGA_3("Omega-3 (ALA)", "g", 1.6);

    /** This nutrient's amount in a scaled [NutrientValues]; null when unreported. */
    fun amountIn(values: NutrientValues): Double? = when (this) {
        VITAMIN_A -> values.vitaminAMicrogramsRae
        VITAMIN_C -> values.vitaminCMg
        VITAMIN_D -> values.vitaminDMicrograms
        VITAMIN_B12 -> values.vitaminB12Micrograms
        IRON -> values.ironMg
        CALCIUM -> values.calciumMg
        POTASSIUM -> values.potassiumMg
        MAGNESIUM -> values.magnesiumMg
        ZINC -> values.zincMg
        OMEGA_3 -> values.omega3Grams
    }
}

/** What can honestly be said about one micronutrient on one day (blueprint 3.7). */
enum class MicroStatus {
    /** Coverage adequate and intake at or above [MicronutrientCoverage.LOW_FRACTION_OF_REFERENCE]. */
    OK,

    /** Coverage adequate and intake below the low threshold — a real signal, not missing data. */
    LOW,

    /**
     * Coverage too thin to judge. "This nutrient wasn't in today's data" must
     * never be presented as "you are low on this nutrient" (blueprint 3.7).
     */
    UNKNOWN,
}

/** One micronutrient's coverage verdict for one day (blueprint 3.7's coverage view). */
data class MicroCoverage(
    val nutrient: Micronutrient,
    /** Known intake for the day; null when no entry reported the nutrient. */
    val intake: Double?,
    /** [intake] as a percentage of the DRI reference; null when intake is unknown. */
    val percentOfReference: Double?,
    /**
     * Share of the day's intake (by known calories) coming from entries that
     * report this nutrient, 0..1 — how much of the day actually carried data
     * for it. Falls back to the share of entries when no entry reports
     * calories at all.
     */
    val calorieCoverage: Double,
    val entriesReporting: Int,
    val entriesTotal: Int,
    val status: MicroStatus,
)

/** One micronutrient's multi-day gap report — the coach's gap flag input (blueprint 3.7). */
data class MicroGapReport(
    val nutrient: Micronutrient,
    val daysTotal: Int,
    /** Days with adequate coverage — the only days that can judge the nutrient. */
    val daysCovered: Int,
    /** Covered days that came in LOW. */
    val daysLow: Int,
    /** Mean % of the DRI reference across covered days; null when no day was covered. */
    val averagePercentOfReference: Double?,
    /**
     * The persistent-gap flag: LOW on a strict majority of covered days, with
     * at least [MicronutrientCoverage.MIN_COVERED_DAYS] covered days. A
     * nutrient that is merely unmeasured on most days never earns this flag.
     */
    val isPersistentGap: Boolean,
)

/**
 * Micronutrient coverage and persistent-gap detection (blueprint 3.7: the
 * coverage view, and the coach flagging "low on magnesium and vitamin D three
 * weeks running").
 *
 * The central honesty rule: a nutrient is only ever judged on the part of the
 * day that reported it. MyFitnessPal-sourced days report no micronutrients at
 * all, so their verdicts are UNKNOWN — a data gap, not a dietary one — and
 * only adequately-covered days count toward a persistent gap.
 */
object MicronutrientCoverage {

    /**
     * A day judges a nutrient only when at least this share of its known
     * calories comes from entries reporting the nutrient. Chosen so a day
     * dominated by macro-only sources (MyFitnessPal) can never convict a
     * nutrient it didn't measure.
     */
    const val MIN_CALORIE_COVERAGE = 0.8

    /**
     * Below this share of the DRI reference, an adequately-covered day counts
     * as LOW. Day-to-day intake swings widely, so a single day at 75–99 % of
     * the reference is ordinary variation, not a flag; the documented default
     * is a judgement call under blueprint 3.15's DRI umbrella, and the
     * multi-day majority rule in [gapReport] is the real signal.
     */
    const val LOW_FRACTION_OF_REFERENCE = 0.75

    /** A persistent gap needs at least this many adequately-covered days in the window. */
    const val MIN_COVERED_DAYS = 3

    /** Coverage verdicts for every tracked micronutrient, for one day's [entries]. */
    fun assessDay(entries: List<FoodLogEntry>): List<MicroCoverage> =
        Micronutrient.entries.map { assess(entries, it) }

    /**
     * Persistent-gap detection over a window of days (each inner list is one
     * day's entries, as logged — days are not re-filtered by date here).
     * Returns a report for every tracked micronutrient, flagged and unflagged
     * alike, with the coverage counts that explain each verdict.
     */
    fun gapReport(days: List<List<FoodLogEntry>>): List<MicroGapReport> =
        Micronutrient.entries.map { nutrient ->
            val daily = days.map { assess(it, nutrient) }
            val covered = daily.filter { it.status != MicroStatus.UNKNOWN }
            val low = covered.count { it.status == MicroStatus.LOW }
            MicroGapReport(
                nutrient = nutrient,
                daysTotal = days.size,
                daysCovered = covered.size,
                daysLow = low,
                averagePercentOfReference = if (covered.isEmpty()) {
                    null
                } else {
                    covered.sumOf { it.percentOfReference ?: 0.0 } / covered.size
                },
                isPersistentGap = covered.size >= MIN_COVERED_DAYS && low * 2 > covered.size,
            )
        }

    private fun assess(entries: List<FoodLogEntry>, nutrient: Micronutrient): MicroCoverage {
        var intakeSum = 0.0
        var reporting = 0
        var coveredCalories = 0.0
        var knownCalories = 0.0
        for (entry in entries) {
            val amounts = entry.nutrients
            val amount = nutrient.amountIn(amounts)
            if (amount != null) {
                intakeSum += amount
                reporting++
            }
            val kcal = amounts.caloriesKcal
            if (kcal != null) {
                knownCalories += kcal
                if (amount != null) coveredCalories += kcal
            }
        }
        val coverage = when {
            knownCalories > 0.0 -> coveredCalories / knownCalories
            entries.isNotEmpty() -> reporting.toDouble() / entries.size
            else -> 0.0
        }
        val intake = if (reporting > 0) intakeSum else null
        val percent = intake?.let { it / nutrient.referenceIntake * 100.0 }
        val status = when {
            coverage < MIN_CALORIE_COVERAGE || intake == null -> MicroStatus.UNKNOWN
            intake < LOW_FRACTION_OF_REFERENCE * nutrient.referenceIntake -> MicroStatus.LOW
            else -> MicroStatus.OK
        }
        return MicroCoverage(
            nutrient = nutrient,
            intake = intake,
            percentOfReference = percent,
            calorieCoverage = coverage,
            entriesReporting = reporting,
            entriesTotal = entries.size,
            status = status,
        )
    }
}
