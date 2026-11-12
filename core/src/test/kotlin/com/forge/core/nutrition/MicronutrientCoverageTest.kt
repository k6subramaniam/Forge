package com.forge.core.nutrition

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class MicronutrientCoverageTest {

    private fun entry(
        id: String,
        source: FoodSource = FoodSource.USDA,
        nutrients: NutrientValues,
    ) = FoodLogEntry(
        id = id,
        timestamp = Instant.parse("2026-01-05T08:00:00Z"),
        meal = MealType.BREAKFAST,
        name = "food-$id",
        source = source,
        quantityGrams = 100.0,
        nutrientsPer100g = nutrients,
    )

    private fun coverageOf(entries: List<FoodLogEntry>, nutrient: Micronutrient): MicroCoverage =
        MicronutrientCoverage.assessDay(entries).first { it.nutrient == nutrient }

    @Test
    fun `reference table carries the cited adult-male DRI values`() {
        // IOM DRI (NIH ODS tabulation): RDA unless AI — potassium and omega-3 (ALA) are AIs.
        assertEquals(900.0, Micronutrient.VITAMIN_A.referenceIntake, 0.0)
        assertEquals(90.0, Micronutrient.VITAMIN_C.referenceIntake, 0.0)
        assertEquals(15.0, Micronutrient.VITAMIN_D.referenceIntake, 0.0)
        assertEquals(2.4, Micronutrient.VITAMIN_B12.referenceIntake, 0.0)
        assertEquals(8.0, Micronutrient.IRON.referenceIntake, 0.0)
        assertEquals(1000.0, Micronutrient.CALCIUM.referenceIntake, 0.0)
        assertEquals(3400.0, Micronutrient.POTASSIUM.referenceIntake, 0.0)
        assertEquals(420.0, Micronutrient.MAGNESIUM.referenceIntake, 0.0)
        assertEquals(11.0, Micronutrient.ZINC.referenceIntake, 0.0)
        assertEquals(1.6, Micronutrient.OMEGA_3.referenceIntake, 0.0)
    }

    @Test
    fun `well-covered genuinely low micro is flagged LOW, adequate micro is OK`() {
        // Two entries, both reporting both micros (coverage 1.0):
        // vitamin D totals 1 + 2 = 3 µg of a 15 µg reference = 20% -> LOW.
        // vitamin C totals 40 + 60 = 100 mg of a 90 mg reference = 111% -> OK.
        val entries = listOf(
            entry("a", nutrients = NutrientValues(caloriesKcal = 500.0, vitaminDMicrograms = 1.0, vitaminCMg = 40.0)),
            entry("b", nutrients = NutrientValues(caloriesKcal = 700.0, vitaminDMicrograms = 2.0, vitaminCMg = 60.0)),
        )
        val vitD = coverageOf(entries, Micronutrient.VITAMIN_D)
        assertEquals(3.0, vitD.intake!!, 1e-9)
        assertEquals(20.0, vitD.percentOfReference!!, 1e-9)
        assertEquals(1.0, vitD.calorieCoverage, 1e-9)
        assertEquals(MicroStatus.LOW, vitD.status)

        val vitC = coverageOf(entries, Micronutrient.VITAMIN_C)
        assertEquals(100.0, vitC.intake!!, 1e-9)
        assertEquals(100.0 / 90.0 * 100.0, vitC.percentOfReference!!, 1e-9)
        assertEquals(MicroStatus.OK, vitC.status)
    }

    @Test
    fun `percent of reference is reported even on a low day`() {
        // 45 mg vitamin C against the 90 mg reference is exactly 50%.
        val entries = listOf(
            entry("a", nutrients = NutrientValues(caloriesKcal = 400.0, vitaminCMg = 45.0)),
        )
        val vitC = coverageOf(entries, Micronutrient.VITAMIN_C)
        assertEquals(50.0, vitC.percentOfReference!!, 1e-9)
        assertEquals(MicroStatus.LOW, vitC.status) // 50% < the 75% low threshold, fully covered
    }

    @Test
    fun `myfitnesspal-only day leaves every micro UNKNOWN`() {
        // MFP meals carry macros only: the micros were never measured, so no
        // verdict is possible — UNKNOWN, never LOW.
        val entries = listOf(
            entry(
                "mfp", source = FoodSource.MYFITNESSPAL,
                nutrients = NutrientValues(caloriesKcal = 800.0, proteinGrams = 50.0),
            ),
        )
        val all = MicronutrientCoverage.assessDay(entries)
        assertTrue(all.all { it.status == MicroStatus.UNKNOWN })
        val vitD = all.first { it.nutrient == Micronutrient.VITAMIN_D }
        assertNull(vitD.intake)
        assertEquals(0.0, vitD.calorieCoverage, 1e-9)
    }

    @Test
    fun `low intake under thin coverage is UNKNOWN, not LOW`() {
        // Iron intake is 2 mg (25% of reference — would be LOW if judged), but
        // it rides on 300 of the day's 1200 kcal: coverage 0.25 is too thin to judge.
        val entries = listOf(
            entry("usda", nutrients = NutrientValues(caloriesKcal = 300.0, ironMg = 2.0)),
            entry(
                "mfp", source = FoodSource.MYFITNESSPAL,
                nutrients = NutrientValues(caloriesKcal = 900.0, proteinGrams = 60.0),
            ),
        )
        val iron = coverageOf(entries, Micronutrient.IRON)
        assertEquals(2.0, iron.intake!!, 1e-9) // the known intake is still reported
        assertEquals(0.25, iron.calorieCoverage, 1e-9)
        assertEquals(MicroStatus.UNKNOWN, iron.status)
    }

    @Test
    fun `coverage exactly at the threshold still judges the nutrient`() {
        // 800 of 1000 kcal report vitamin D: coverage 0.8 meets the minimum.
        // Intake 20 µg of 15 µg is adequate -> OK (not UNKNOWN, not LOW).
        val entries = listOf(
            entry("a", nutrients = NutrientValues(caloriesKcal = 800.0, vitaminDMicrograms = 20.0)),
            entry("b", nutrients = NutrientValues(caloriesKcal = 200.0)),
        )
        val vitD = coverageOf(entries, Micronutrient.VITAMIN_D)
        assertEquals(0.8, vitD.calorieCoverage, 1e-9)
        assertEquals(MicroStatus.OK, vitD.status)
    }

    @Test
    fun `persistent gap requires a majority of covered days low`() {
        // Four fully-covered days. Vitamin D is 3 µg (20%) every day -> LOW x4 -> gap.
        // Magnesium is 100 mg (LOW) on two days and 500 mg (OK) on two -> 2/4 is
        // not a majority -> no gap.
        fun day(id: String, magnesium: Double) = listOf(
            entry(id, nutrients = NutrientValues(caloriesKcal = 1000.0, vitaminDMicrograms = 3.0, magnesiumMg = magnesium)),
        )
        val report = MicronutrientCoverage.gapReport(
            listOf(day("d1", 100.0), day("d2", 100.0), day("d3", 500.0), day("d4", 500.0)),
        )
        val vitD = report.first { it.nutrient == Micronutrient.VITAMIN_D }
        assertTrue(vitD.isPersistentGap)
        assertEquals(4, vitD.daysCovered)
        assertEquals(4, vitD.daysLow)
        assertEquals(20.0, vitD.averagePercentOfReference!!, 1e-9)

        val magnesium = report.first { it.nutrient == Micronutrient.MAGNESIUM }
        assertFalse(magnesium.isPersistentGap)
        assertEquals(4, magnesium.daysCovered)
        assertEquals(2, magnesium.daysLow)
    }

    @Test
    fun `persistent gap requires enough covered days`() {
        // Zinc is LOW on both days that measured it, but only 2 of the 4 days
        // carried zinc data at all — below the 3-covered-day minimum, so the
        // honest verdict is "not enough covered days", not a gap.
        val lowZincDay = listOf(
            entry("z", nutrients = NutrientValues(caloriesKcal = 1000.0, zincMg = 2.0)),
        )
        val mfpDay = listOf(
            entry(
                "m", source = FoodSource.MYFITNESSPAL,
                nutrients = NutrientValues(caloriesKcal = 1000.0, proteinGrams = 60.0),
            ),
        )
        val report = MicronutrientCoverage.gapReport(listOf(lowZincDay, lowZincDay, mfpDay, mfpDay))
        val zinc = report.first { it.nutrient == Micronutrient.ZINC }
        assertEquals(4, zinc.daysTotal)
        assertEquals(2, zinc.daysCovered)
        assertEquals(2, zinc.daysLow)
        assertFalse(zinc.isPersistentGap)
    }

    @Test
    fun `empty day is all UNKNOWN with no intake`() {
        val all = MicronutrientCoverage.assessDay(emptyList())
        assertEquals(Micronutrient.entries.size, all.size)
        assertTrue(all.all { it.status == MicroStatus.UNKNOWN })
        assertTrue(all.all { it.intake == null && it.calorieCoverage == 0.0 })
    }
}
