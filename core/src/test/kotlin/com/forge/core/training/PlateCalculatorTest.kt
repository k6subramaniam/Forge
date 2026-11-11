package com.forge.core.training

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PlateCalculatorTest {

    @Test
    fun `60 kg on a 20 kg bar is a single 20 plate per side`() {
        val load = PlateCalculator.calculate(targetWeightKg = 60.0)
        assertEquals(listOf(20.0), load.platesPerSide)
        assertEquals(60.0, load.achievedWeightKg, 0.0)
        assertTrue(load.isExact)
    }

    @Test
    fun `100 kg loads 25 and 15 per side`() {
        val load = PlateCalculator.calculate(targetWeightKg = 100.0)
        assertEquals(listOf(25.0, 15.0), load.platesPerSide)
        assertEquals(100.0, load.achievedWeightKg, 0.0)
        assertTrue(load.isExact)
    }

    @Test
    fun `fractional target uses the small plates exactly`() {
        val load = PlateCalculator.calculate(targetWeightKg = 102.5)
        assertEquals(listOf(25.0, 15.0, 1.25), load.platesPerSide)
        assertEquals(102.5, load.achievedWeightKg, 0.0)
        assertTrue(load.isExact)
    }

    @Test
    fun `140 kg stacks three plates per side`() {
        // 60 kg per side, greedy largest-first: 25 + 25 + 10.
        val load = PlateCalculator.calculate(targetWeightKg = 140.0)
        assertEquals(listOf(25.0, 25.0, 10.0), load.platesPerSide)
        assertTrue(load.isExact)
    }

    @Test
    fun `unachievable target reports the closest load and the shortfall`() {
        // 61 kg needs 20.5 kg per side; the smallest plate is 1.25, so 20 per side is closest.
        val load = PlateCalculator.calculate(targetWeightKg = 61.0)
        assertEquals(listOf(20.0), load.platesPerSide)
        assertEquals(60.0, load.achievedWeightKg, 0.0)
        assertEquals(1.0, load.shortfallKg, 0.0)
        assertFalse(load.isExact)
    }

    @Test
    fun `target equal to the bar loads no plates`() {
        val load = PlateCalculator.calculate(targetWeightKg = 20.0)
        assertEquals(emptyList<Double>(), load.platesPerSide)
        assertEquals(20.0, load.achievedWeightKg, 0.0)
        assertTrue(load.isExact)
    }

    @Test
    fun `target below the bar falls back to the empty bar`() {
        val load = PlateCalculator.calculate(targetWeightKg = 15.0)
        assertEquals(emptyList<Double>(), load.platesPerSide)
        assertEquals(20.0, load.achievedWeightKg, 0.0)
        assertEquals(-5.0, load.shortfallKg, 0.0)
        assertFalse(load.isExact)
    }

    @Test
    fun `respects a non-standard bar weight`() {
        val load = PlateCalculator.calculate(targetWeightKg = 55.0, barWeightKg = 15.0)
        assertEquals(listOf(20.0), load.platesPerSide)
        assertEquals(55.0, load.achievedWeightKg, 0.0)
        assertTrue(load.isExact)
    }

    @Test
    fun `rejects non-positive target`() {
        assertThrows<IllegalArgumentException> { PlateCalculator.calculate(targetWeightKg = 0.0) }
    }
}
