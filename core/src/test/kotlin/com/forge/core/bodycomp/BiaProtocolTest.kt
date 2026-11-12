package com.forge.core.bodycomp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class BiaProtocolTest {

    private val day: LocalDate = LocalDate.of(2026, 11, 10)
    private val good = BiaConditions(fasted = true, postVoid = true, usualHydration = true)

    private fun reading(time: LocalTime, conditions: BiaConditions = good, fat: Double = 21.0) =
        BiaReading(LocalDateTime.of(day, time), fat, conditions)

    @Test
    fun `a morning reading under all conditions enters the trend`() {
        val result = BiaProtocol.grade(reading(LocalTime.of(7, 0)))
        assertTrue(result.acceptedIntoTrend)
        assertTrue(result.failedChecks.isEmpty())
    }

    @Test
    fun `an evening reading is kept out of the trend, with the reason`() {
        val result = BiaProtocol.grade(reading(LocalTime.of(19, 30)))
        assertFalse(result.acceptedIntoTrend)
        assertEquals(listOf(BiaCheck.MORNING), result.failedChecks)
    }

    @Test
    fun `morning runs from 4 am up to noon`() {
        assertFalse(BiaProtocol.grade(reading(LocalTime.of(3, 59))).acceptedIntoTrend)
        assertTrue(BiaProtocol.grade(reading(LocalTime.of(4, 0))).acceptedIntoTrend)
        assertTrue(BiaProtocol.grade(reading(LocalTime.of(11, 59))).acceptedIntoTrend)
        assertFalse(BiaProtocol.grade(reading(LocalTime.NOON)).acceptedIntoTrend)
    }

    @Test
    fun `every failed condition is reported, in checklist order`() {
        val sloppy = BiaConditions(fasted = false, postVoid = false, usualHydration = true)
        val result = BiaProtocol.grade(reading(LocalTime.of(7, 0), sloppy))
        assertEquals(listOf(BiaCheck.FASTED, BiaCheck.POST_VOID), result.failedChecks)

        val dehydrated = good.copy(usualHydration = false)
        assertEquals(
            listOf(BiaCheck.MORNING, BiaCheck.USUAL_HYDRATION),
            BiaProtocol.grade(reading(LocalTime.of(20, 0), dehydrated)).failedChecks,
        )
    }

    @Test
    fun `only readings that passed become ensemble members`() {
        val readings = listOf(
            reading(LocalTime.of(6, 45), fat = 21.0),
            reading(LocalTime.of(21, 0), fat = 25.0),
            reading(LocalTime.of(7, 10), good.copy(fasted = false), fat = 26.0),
        )
        val estimates = BiaProtocol.acceptedEstimates(readings)
        assertEquals(1, estimates.size)
        val only = estimates.single()
        assertEquals(CompositionMethod.WRIST_BIA, only.method)
        assertEquals(21.0, only.fatPercent, 1e-9)
        assertEquals(day, only.date)
        assertEquals(MethodAccuracy.PROVISIONAL.of(CompositionMethod.WRIST_BIA), only.standardErrorPp)
    }

    @Test
    fun `the checklist asks for every condition`() {
        assertEquals(BiaCheck.entries.toList(), BiaProtocol.checklist)
        assertTrue(BiaProtocol.checklist.all { it.prompt.isNotBlank() })
    }
}
