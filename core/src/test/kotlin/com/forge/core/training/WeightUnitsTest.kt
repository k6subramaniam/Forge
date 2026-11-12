package com.forge.core.training

import com.forge.core.data.SampleData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WeightUnitsTest {

    @Test
    fun `100 kg is 220_46 lb`() {
        assertEquals(220.46226218487757, WeightConverter.kgToLb(100.0), 1e-9)
        assertEquals(100.0, WeightConverter.lbToKg(220.46226218487757), 1e-9)
    }

    @Test
    fun `conversion round-trips without drift`() {
        for (kg in listOf(0.5, 20.0, 60.0, 102.5, 180.0)) {
            assertEquals(kg, WeightConverter.lbToKg(WeightConverter.kgToLb(kg)), 1e-9)
            assertEquals(kg, WeightConverter.convert(kg, WeightUnit.KG, WeightUnit.KG), 0.0)
            assertEquals(
                WeightConverter.kgToLb(kg),
                WeightConverter.convert(kg, WeightUnit.KG, WeightUnit.LB),
                0.0,
            )
        }
    }

    @Test
    fun `lb plate set is selected for the lb unit system`() {
        assertEquals(
            listOf(45.0, 35.0, 25.0, 10.0, 5.0, 2.5, 1.25),
            PlateCalculator.platesFor(WeightUnit.LB),
        )
        assertEquals(45.0, PlateCalculator.defaultBarWeight(WeightUnit.LB), 0.0)
        assertEquals(PlateCalculator.PLATES_KG, PlateCalculator.platesFor(WeightUnit.KG))
        assertEquals(20.0, PlateCalculator.defaultBarWeight(WeightUnit.KG), 0.0)
    }

    @Test
    fun `135 lb on a 45 lb bar is a single 45 plate per side`() {
        val load = PlateCalculator.calculate(targetWeight = 135.0, unit = WeightUnit.LB)
        assertEquals(listOf(45.0), load.platesPerSide)
        assertEquals(135.0, load.achievedWeight, 0.0)
        assertTrue(load.isExact)
    }

    @Test
    fun `225 lb loads two 45 plates per side`() {
        // (225 − 45) / 2 = 90 per side; greedy heaviest-first takes 45 + 45.
        val load = PlateCalculator.calculate(targetWeight = 225.0, unit = WeightUnit.LB)
        assertEquals(listOf(45.0, 45.0), load.platesPerSide)
        assertEquals(225.0, load.achievedWeight, 0.0)
        assertTrue(load.isExact)
    }

    @Test
    fun `140 lb uses the 2_5 lb plate exactly`() {
        // (140 − 45) / 2 = 47.5 per side: 45 + 2.5.
        val load = PlateCalculator.calculate(targetWeight = 140.0, unit = WeightUnit.LB)
        assertEquals(listOf(45.0, 2.5), load.platesPerSide)
        assertTrue(load.isExact)
    }

    @Test
    fun `unachievable lb target reports the closest load and the shortfall`() {
        // 136 lb needs 45.5 per side; the closest loadable is 45 per side.
        val load = PlateCalculator.calculate(targetWeight = 136.0, unit = WeightUnit.LB)
        assertEquals(listOf(45.0), load.platesPerSide)
        assertEquals(135.0, load.achievedWeight, 0.0)
        assertEquals(1.0, load.shortfall, 0.0)
        assertFalse(load.isExact)
    }

    @Test
    fun `kg calculation through the unit API matches the kg API`() {
        val viaUnit = PlateCalculator.calculate(targetWeight = 100.0, unit = WeightUnit.KG)
        val viaKg = PlateCalculator.calculate(targetWeightKg = 100.0)
        assertEquals(viaKg.platesPerSide, viaUnit.platesPerSide)
        assertEquals(viaKg.achievedWeightKg, viaUnit.achievedWeight, 0.0)
        assertEquals(viaKg.shortfallKg, viaUnit.shortfall, 0.0)
    }

    @Test
    fun `overload increments per unit system`() {
        // kg: the original rule, unchanged.
        assertEquals(2.5, ProgressiveOverload.incrementFor(SampleData.benchPress, WeightUnit.KG), 0.0)
        assertEquals(5.0, ProgressiveOverload.incrementFor(SampleData.squat, WeightUnit.KG), 0.0)
        // lb: +5 upper / +10 lower (decided 2026-11-11).
        assertEquals(5.0, ProgressiveOverload.incrementFor(SampleData.benchPress, WeightUnit.LB), 0.0)
        assertEquals(10.0, ProgressiveOverload.incrementFor(SampleData.squat, WeightUnit.LB), 0.0)
    }

    @Test
    fun `a pound target that round-tripped through kg still gets every plate`() {
        // 150 lb stored as kg comes back as 149.99999999999997 lb.
        val roundTripped = WeightConverter.kgToLb(WeightConverter.lbToKg(150.0))
        assertTrue(roundTripped < 150.0)
        val load = PlateCalculator.calculate(roundTripped, WeightUnit.LB)
        assertEquals(listOf(45.0, 5.0, 2.5), load.platesPerSide)
        assertEquals(150.0, load.achievedWeight, 1e-9)
        assertEquals(0.0, load.shortfall, 0.0)
    }
}
