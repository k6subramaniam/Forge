package com.forge.core.nutrition

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DietLoggingTest {

    private val date: LocalDate = LocalDate.of(2026, 1, 5)
    private val utc: ZoneId = ZoneId.of("UTC")

    private fun entry(
        id: String,
        meal: MealType = MealType.BREAKFAST,
        source: FoodSource = FoodSource.USDA,
        grams: Double = 100.0,
        time: String = "2026-01-05T08:00:00Z",
        nutrients: NutrientValues,
        uncertainty: Double? = null,
    ) = FoodLogEntry(
        id = id,
        timestamp = Instant.parse(time),
        meal = meal,
        name = "food-$id",
        source = source,
        quantityGrams = grams,
        nutrientsPer100g = nutrients,
        uncertaintyFraction = uncertainty,
    )

    // -- Scaling --------------------------------------------------------------

    @Test
    fun `per-100g profile scales to the logged portion`() {
        // Greek yogurt per 100 g: 97 kcal, 9.0 protein, 3.6 carbs, 5.0 fat, 110 mg calcium.
        // A 150 g portion is 1.5x: 145.5 kcal, 13.5 protein, 5.4 carbs, 7.5 fat, 165 calcium.
        val e = entry(
            "yogurt", grams = 150.0,
            nutrients = NutrientValues(
                caloriesKcal = 97.0, proteinGrams = 9.0, carbsGrams = 3.6,
                fatGrams = 5.0, calciumMg = 110.0,
            ),
        )
        assertEquals(145.5, e.nutrients.caloriesKcal!!, 1e-9)
        assertEquals(13.5, e.nutrients.proteinGrams!!, 1e-9)
        assertEquals(5.4, e.nutrients.carbsGrams!!, 1e-9)
        assertEquals(7.5, e.nutrients.fatGrams!!, 1e-9)
        assertEquals(165.0, e.nutrients.calciumMg!!, 1e-9)
    }

    @Test
    fun `fractional portions scale exactly`() {
        // Cheddar per 100 g: 416 kcal, 25.0 protein. A 25 g portion is a quarter:
        // 104 kcal, 6.25 protein.
        val e = entry(
            "cheddar", grams = 25.0,
            nutrients = NutrientValues(caloriesKcal = 416.0, proteinGrams = 25.0),
        )
        assertEquals(104.0, e.nutrients.caloriesKcal!!, 1e-9)
        assertEquals(6.25, e.nutrients.proteinGrams!!, 1e-9)
    }

    @Test
    fun `unknown nutrients stay unknown after scaling`() {
        // No zero-filling: a profile silent on fibre and sugar scales to a
        // portion that is equally silent on them.
        val e = entry(
            "mystery", grams = 250.0,
            nutrients = NutrientValues(caloriesKcal = 200.0, proteinGrams = 10.0),
        )
        assertEquals(500.0, e.nutrients.caloriesKcal!!, 1e-9)
        assertNull(e.nutrients.fibreGrams)
        assertNull(e.nutrients.sugarGrams)
        assertNull(e.nutrients.vitaminDMicrograms)
    }

    // -- Daily aggregation ------------------------------------------------------

    @Test
    fun `totals sum reported values and expose partial completeness`() {
        val day = DietLog.summarise(
            listOf(
                // 100 g: protein 31, kcal 165; silent on fibre and sugar.
                entry("e1", nutrients = NutrientValues(caloriesKcal = 165.0, proteinGrams = 31.0)),
                // 50 g of a per-100 g profile (130 kcal, 2.7 protein, 0.4 fibre) -> 65, 1.35, 0.2.
                entry(
                    "e2", grams = 50.0,
                    nutrients = NutrientValues(caloriesKcal = 130.0, proteinGrams = 2.7, fibreGrams = 0.4),
                ),
                // MFP meal total: 500 kcal, 20 protein, 3 fibre; silent on sugar.
                entry(
                    "e3", source = FoodSource.MYFITNESSPAL,
                    nutrients = NutrientValues(caloriesKcal = 500.0, proteinGrams = 20.0, fibreGrams = 3.0),
                ),
            ),
            date, utc,
        )
        // Protein: 31 + 1.35 + 20 = 52.35, reported by all three.
        assertEquals(52.35, day.macros.protein.value!!, 1e-9)
        assertTrue(day.macros.protein.isComplete)
        // Calories: 165 + 65 + 500 = 730, complete.
        assertEquals(730.0, day.macros.calories.value!!, 1e-9)
        assertTrue(day.macros.calories.isComplete)
        // Fibre: 0.2 + 3 = 3.2 from two of three entries — a lower bound, flagged partial.
        assertEquals(3.2, day.macros.fibre.value!!, 1e-9)
        assertTrue(day.macros.fibre.isPartial)
        assertEquals(2, day.macros.fibre.entriesReporting)
        assertEquals(3, day.macros.fibre.entriesTotal)
        // Sugar: nobody reported it — unknown, not zero.
        assertNull(day.macros.sugar.value)
        assertTrue(day.macros.sugar.isUnknown)
    }

    @Test
    fun `per-meal subtotals group the day by meal`() {
        val day = DietLog.summarise(
            listOf(
                entry("b1", meal = MealType.BREAKFAST, nutrients = NutrientValues(caloriesKcal = 300.0)),
                entry("b2", meal = MealType.BREAKFAST, nutrients = NutrientValues(caloriesKcal = 200.0)),
                entry("l1", meal = MealType.LUNCH, nutrients = NutrientValues(caloriesKcal = 700.0)),
            ),
            date, utc,
        )
        assertEquals(setOf(MealType.BREAKFAST, MealType.LUNCH), day.mealTotals.keys)
        assertEquals(500.0, day.mealTotals.getValue(MealType.BREAKFAST).calories.value!!, 1e-9)
        assertEquals(700.0, day.mealTotals.getValue(MealType.LUNCH).calories.value!!, 1e-9)
        assertEquals(1200.0, day.macros.calories.value!!, 1e-9)
    }

    @Test
    fun `entries are assigned to days in the given zone`() {
        val entries = listOf(
            entry("midday", time = "2026-01-05T12:00:00Z", nutrients = NutrientValues(caloriesKcal = 100.0)),
            // 02:00 UTC is 21:00 the previous evening in Toronto.
            entry("late", time = "2026-01-05T02:00:00Z", nutrients = NutrientValues(caloriesKcal = 999.0)),
        )
        val utcDay = DietLog.summarise(entries, date, utc)
        assertEquals(1099.0, utcDay.macros.calories.value!!, 1e-9)

        val toronto = ZoneId.of("America/Toronto")
        val torontoDay = DietLog.summarise(entries, date, toronto)
        assertEquals(100.0, torontoDay.macros.calories.value!!, 1e-9)
        val torontoPrev = DietLog.summarise(entries, date.minusDays(1), toronto)
        assertEquals(999.0, torontoPrev.macros.calories.value!!, 1e-9)
    }

    @Test
    fun `myfitnesspal entries contribute their meal totals and label the day`() {
        val day = DietLog.summarise(
            listOf(
                entry(
                    "mfp-lunch", meal = MealType.LUNCH, source = FoodSource.MYFITNESSPAL,
                    nutrients = NutrientValues(
                        caloriesKcal = 650.0, proteinGrams = 45.0, carbsGrams = 60.0,
                        fatGrams = 22.0, fibreGrams = 5.0, sugarGrams = 8.0, sodiumMg = 800.0,
                    ),
                ),
            ),
            date, utc,
        )
        assertEquals(650.0, day.macros.calories.value!!, 1e-9)
        assertEquals(45.0, day.macros.protein.value!!, 1e-9)
        assertEquals(800.0, day.macros.sodium.value!!, 1e-9)
        assertEquals(setOf(FoodSource.MYFITNESSPAL), day.sources)
    }

    @Test
    fun `photo estimate entries flag themselves and their day`() {
        val photo = entry(
            "photo", source = FoodSource.PHOTO_ESTIMATE, uncertainty = 0.25,
            nutrients = NutrientValues(caloriesKcal = 700.0),
        )
        assertTrue(photo.isEstimate)
        assertEquals(0.25, photo.uncertaintyFraction!!, 1e-9)
        assertTrue(DietLog.summarise(listOf(photo), date, utc).hasEstimates)

        val manual = entry("manual", source = FoodSource.MANUAL, nutrients = NutrientValues(caloriesKcal = 100.0))
        assertFalse(manual.isEstimate)
        assertFalse(DietLog.summarise(listOf(manual), date, utc).hasEstimates)
    }

    // -- Target comparison ------------------------------------------------------

    @Test
    fun `comparison reports remaining and over amounts against targets`() {
        // One entry carrying the whole day: 1180 kcal, 160 protein, 50 fat, 100 carbs, 20 fibre.
        val day = DietLog.summarise(
            listOf(
                entry(
                    "all",
                    nutrients = NutrientValues(
                        caloriesKcal = 1180.0, proteinGrams = 160.0, fatGrams = 50.0,
                        carbsGrams = 100.0, fibreGrams = 20.0,
                    ),
                ),
            ),
            date, utc,
        )
        val cmp = DietLog.compare(day, MacroTargets(2000, 150, 70, 250, 30))
        // Calories: 1180 of 2000 -> 820 remaining, none over.
        assertEquals(1180.0, cmp.calories.consumed!!, 1e-9)
        assertEquals(820.0, cmp.calories.remaining!!, 1e-9)
        assertEquals(0.0, cmp.calories.overBy!!, 1e-9)
        assertTrue(cmp.calories.isComplete)
        // Protein: 160 of 150 -> nothing remaining, 10 over.
        assertEquals(0.0, cmp.protein.remaining!!, 1e-9)
        assertEquals(10.0, cmp.protein.overBy!!, 1e-9)
        // Fat: 50 of 70 -> 20 remaining. Fibre: 20 of 30 -> 10 remaining.
        assertEquals(20.0, cmp.fat.remaining!!, 1e-9)
        assertEquals(10.0, cmp.fibre.remaining!!, 1e-9)
    }

    @Test
    fun `comparison of a partial total is flagged as a lower bound`() {
        val day = DietLog.summarise(
            listOf(
                entry("a", nutrients = NutrientValues(caloriesKcal = 500.0, proteinGrams = 50.0)),
                entry("b", nutrients = NutrientValues(caloriesKcal = 300.0)), // silent on protein
            ),
            date, utc,
        )
        val cmp = DietLog.compare(day, MacroTargets(2000, 150, 70, 250, 30))
        assertEquals(50.0, cmp.protein.consumed!!, 1e-9)
        assertFalse(cmp.protein.isComplete)
        assertTrue(cmp.calories.isComplete)
        assertEquals(800.0, cmp.calories.consumed!!, 1e-9)
    }

    @Test
    fun `comparison picks training or rest targets from the plan`() {
        val average = MacroTargets(2400, 150, 67, 280, 34)
        val plan = EnergyPlan(
            bmrKcal = 1700,
            target = CalorieTarget(2400, 2400, 0, 0.0, false),
            averageDay = average,
            trainingDay = MacroTargets(2520, 150, 67, 310, 35),
            restDay = MacroTargets(2210, 150, 67, 235, 31),
            daySplitFloorApplied = false,
        )
        val day = DietLog.summarise(
            listOf(entry("x", nutrients = NutrientValues(caloriesKcal = 1000.0))),
            date, utc,
        )
        assertEquals(2520, DietLog.compare(day, plan, trainingDay = true).calories.target)
        assertEquals(2210, DietLog.compare(day, plan, trainingDay = false).calories.target)
    }

    // -- IntakeDay production ---------------------------------------------------

    @Test
    fun `daily totals produce the IntakeDay adaptive TDEE consumes`() {
        val day = DietLog.summarise(
            listOf(entry("x", nutrients = NutrientValues(caloriesKcal = 1180.4))),
            date, utc,
        )
        val intakeDay = day.toIntakeDay()
        assertEquals(date, intakeDay?.date)
        assertEquals(1180, intakeDay?.caloriesKcal) // 1180.4 rounds to the whole kcal IntakeDay carries
    }

    @Test
    fun `a day with no calorie data produces no IntakeDay`() {
        // An unknown-intake day must not masquerade as a 0 kcal day.
        val day = DietLog.summarise(
            listOf(entry("x", nutrients = NutrientValues(proteinGrams = 10.0))),
            date, utc,
        )
        assertTrue(day.macros.calories.isUnknown)
        assertNull(day.toIntakeDay())
    }

    // -- Empty day ---------------------------------------------------------------

    @Test
    fun `empty day summarises to unknowns, not zeros`() {
        val day = DietLog.summarise(emptyList(), date, utc)
        assertEquals(0, day.entryCount)
        assertNull(day.macros.calories.value)
        assertEquals(0, day.macros.calories.entriesTotal)
        assertTrue(day.mealTotals.isEmpty())
        assertTrue(day.sources.isEmpty())
        assertFalse(day.hasEstimates)
        assertNull(day.toIntakeDay())
    }
}
