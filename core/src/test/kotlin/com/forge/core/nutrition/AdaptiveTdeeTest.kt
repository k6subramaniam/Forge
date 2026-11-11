package com.forge.core.nutrition

import com.forge.core.model.ActivityLevel
import com.forge.core.model.EquipmentAccess
import com.forge.core.model.ExperienceLevel
import com.forge.core.model.GoalProfile
import com.forge.core.model.GoalType
import com.forge.core.model.Sex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class AdaptiveTdeeTest {

    private val start: LocalDate = LocalDate.of(2026, 1, 1)
    private val end: LocalDate = start.plusDays(20) // a 21-day window: Jan 1..Jan 21

    private fun profile(
        goal: GoalType = GoalType.MAINTAIN,
        sex: Sex = Sex.MALE,
        age: Int = 30,
        heightCm: Double = 180.0,
        weightKg: Double = 80.0,
        activity: ActivityLevel = ActivityLevel.MODERATE,
    ) = GoalProfile(
        goal = goal,
        ageYears = age,
        sex = sex,
        heightCm = heightCm,
        weightKg = weightKg,
        trainingDaysPerWeek = 4,
        experienceLevel = ExperienceLevel.INTERMEDIATE,
        equipmentAccess = EquipmentAccess.FULL_GYM,
        activityLevel = activity,
    )

    /** [days] of intake at a flat [kcal], starting [from]. */
    private fun intake(from: LocalDate, days: Int, kcal: Int): List<IntakeDay> =
        (0 until days).map { IntakeDay(from.plusDays(it.toLong()), kcal) }

    /** Daily weigh-ins over the 21-day window, changing linearly by [totalChangeKg]. */
    private fun weighIns(firstKg: Double, totalChangeKg: Double): List<WeighIn> =
        (0..20).map { WeighIn(start.plusDays(it.toLong()), firstKg + totalChangeKg * it / 20.0) }

    private fun flatWeighIns(kg: Double): List<WeighIn> = weighIns(kg, 0.0)

    // -- Back-calculation -------------------------------------------------------------

    @Test
    fun `stable weight back-calculates maintenance to average intake`() {
        // 21 intake days at 2600, weight flat at 80.0: stored energy = 0,
        // so TDEE = mean intake = 2600 exactly (formula prior here is 2759).
        val result = AdaptiveTdee.estimate(profile(), flatWeighIns(80.0), intake(start, 21, 2600), end)
        assertEquals(TdeeBasis.BACK_CALCULATED, result.basis)
        assertEquals(2600, result.maintenanceKcal)
        assertEquals(2600, result.backCalculatedTdeeKcal)
        assertEquals(2759, result.formulaTdeeKcal)
        assertEquals(2600.0, result.averageIntakeKcal!!, 0.001)
        assertEquals(0.0, result.trendChangeKg!!, 0.001)
        assertEquals(21, result.intakeDaysUsed)
        assertEquals(21, result.weighInsUsed)
        assertEquals(20L, result.weighInSpanDays)
    }

    @Test
    fun `losing window back-calculates tdee above intake`() {
        // Intake 2200/day; weight falls 85.0 → 84.3 (−0.7 kg over the 20-day span).
        // Released energy: 0.7 × 7700 / 20 = 269.5 kcal/day → TDEE = 2200 + 269.5 = 2469.5 → 2470.
        val result = AdaptiveTdee.estimate(profile(), weighIns(85.0, -0.7), intake(start, 21, 2200), end)
        assertEquals(TdeeBasis.BACK_CALCULATED, result.basis)
        assertEquals(2470, result.backCalculatedTdeeKcal)
        assertEquals(-0.7, result.trendChangeKg!!, 0.001)
    }

    @Test
    fun `gaining window back-calculates tdee below intake`() {
        // Intake 3000/day; weight rises 75.0 → 75.7 (+0.7 kg over the span).
        // Stored energy: 0.7 × 7700 / 20 = 269.5 kcal/day → TDEE = 3000 − 269.5 = 2730.5
        // → 2730 (roundToInt breaks the .5 tie to even).
        val result = AdaptiveTdee.estimate(profile(), weighIns(75.0, 0.7), intake(start, 21, 3000), end)
        assertEquals(TdeeBasis.BACK_CALCULATED, result.basis)
        assertEquals(2730, result.backCalculatedTdeeKcal)
    }

    // -- Insufficient data falls back to the formula, labelled ---------------------------

    @Test
    fun `too few intake days falls back to formula`() {
        val result = AdaptiveTdee.estimate(profile(), flatWeighIns(80.0), intake(start, 5, 2600), end)
        assertEquals(TdeeBasis.FORMULA, result.basis)
        assertEquals(2759, result.maintenanceKcal)
        assertNull(result.backCalculatedTdeeKcal)
        assertEquals(5, result.intakeDaysUsed)
    }

    @Test
    fun `too few weigh-ins falls back to formula`() {
        // 3 weigh-ins spanning the full window — span is fine, count is not.
        val sparse = listOf(
            WeighIn(start, 80.0),
            WeighIn(start.plusDays(10), 80.0),
            WeighIn(end, 80.0),
        )
        val result = AdaptiveTdee.estimate(profile(), sparse, intake(start, 21, 2600), end)
        assertEquals(TdeeBasis.FORMULA, result.basis)
        assertEquals(3, result.weighInsUsed)
    }

    @Test
    fun `weigh-ins spanning under two weeks fall back to formula`() {
        // Daily weigh-ins but only for the last week of the window: span 6 < 14.
        val lateOnly = (0..6).map { WeighIn(start.plusDays(14L + it), 80.0) }
        val result = AdaptiveTdee.estimate(profile(), lateOnly, intake(start, 21, 2600), end)
        assertEquals(TdeeBasis.FORMULA, result.basis)
        assertEquals(6L, result.weighInSpanDays)
    }

    @Test
    fun `data outside the window is ignored`() {
        // December 2025 feast logs (5000 kcal, heavier body) must not leak into
        // the January window: window starts Jan 1 for a 21-day window ending Jan 21.
        val december = LocalDate.of(2025, 12, 1)
        val intakeAll = intake(december, 10, 5000) + intake(start, 21, 2600)
        val weighAll = (0..9).map { WeighIn(december.plusDays(it.toLong()), 95.0) } + flatWeighIns(80.0)
        val result = AdaptiveTdee.estimate(profile(), weighAll, intakeAll, end)
        assertEquals(TdeeBasis.BACK_CALCULATED, result.basis)
        assertEquals(21, result.intakeDaysUsed)
        assertEquals(21, result.weighInsUsed)
        assertEquals(2600, result.maintenanceKcal)
        assertEquals(start, result.windowStart)
    }

    // -- Weekly adjustment -----------------------------------------------------------

    /** A trend with a chosen rate, built directly — the adjustment rule reads only these fields. */
    private fun trend(rate: Double?, spanDays: Long = 20, latestKg: Double = 80.0) = WeightTrend(
        points = emptyList(),
        weighInCount = 15,
        spanDays = spanDays,
        latestTrendKg = latestKg,
        weeklyRateKgPerWeek = rate,
    )

    @Test
    fun `on track trend holds the target`() {
        // LOSE_FAT at 80 kg intends −0.4 kg/week; measured −0.35 → deviation +0.05 ≤ 0.1 → hold.
        val adj = AdaptiveTdee.weeklyAdjustment(profile(GoalType.LOSE_FAT), 2319, trend(-0.35))
        assertEquals(AdjustmentDirection.HOLD, adj.direction)
        assertEquals(AdjustmentReason.ON_TRACK, adj.reason)
        assertEquals(0, adj.deltaKcal)
        assertEquals(2319, adj.suggestedTargetKcal)
    }

    @Test
    fun `losing too slowly decreases by the large step`() {
        // Measured −0.05 vs intended −0.4 → deviation +0.35 > 0.25 → −200 kcal.
        val adj = AdaptiveTdee.weeklyAdjustment(profile(GoalType.LOSE_FAT), 2319, trend(-0.05))
        assertEquals(AdjustmentDirection.DECREASE, adj.direction)
        assertEquals(AdjustmentReason.OFF_TARGET, adj.reason)
        assertEquals(-200, adj.deltaKcal)
        assertEquals(2119, adj.suggestedTargetKcal)
        assertFalse(adj.floorApplied)
    }

    @Test
    fun `small miss decreases by the small step`() {
        // Measured −0.22 vs intended −0.4 → deviation +0.18 ≤ 0.25 → −100 kcal.
        val adj = AdaptiveTdee.weeklyAdjustment(profile(GoalType.LOSE_FAT), 2319, trend(-0.22))
        assertEquals(AdjustmentDirection.DECREASE, adj.direction)
        assertEquals(-100, adj.deltaKcal)
        assertEquals(2219, adj.suggestedTargetKcal)
    }

    @Test
    fun `losing too fast increases the target`() {
        // Measured −0.95 vs intended −0.4 → deviation −0.55 → +200 kcal.
        val adj = AdaptiveTdee.weeklyAdjustment(profile(GoalType.LOSE_FAT), 2319, trend(-0.95))
        assertEquals(AdjustmentDirection.INCREASE, adj.direction)
        assertEquals(200, adj.deltaKcal)
        assertEquals(2519, adj.suggestedTargetKcal)
    }

    @Test
    fun `gaining too slowly on a build increases the target`() {
        // BUILD_MUSCLE at 80 kg intends +0.2 kg/week; measured −0.1 → deviation −0.3 → +200.
        val adj = AdaptiveTdee.weeklyAdjustment(profile(GoalType.BUILD_MUSCLE), 2979, trend(-0.1))
        assertEquals(AdjustmentDirection.INCREASE, adj.direction)
        assertEquals(200, adj.deltaKcal)
        assertEquals(0.2, adj.targetRateKgPerWeek, 0.001)
    }

    @Test
    fun `gaining on a maintain goal decreases the target`() {
        // MAINTAIN intends 0.0; measured +0.3 → deviation +0.3 → −200.
        val adj = AdaptiveTdee.weeklyAdjustment(profile(GoalType.MAINTAIN), 2759, trend(0.3))
        assertEquals(AdjustmentDirection.DECREASE, adj.direction)
        assertEquals(-200, adj.deltaKcal)
    }

    @Test
    fun `decrease is blocked at the bmr floor`() {
        // Small woman (BMR 1082.75 → floor 1083) already eating at the floor.
        // LOSE_FAT at trend weight 50 kg intends −0.25; measured −0.05 →
        // deviation +0.2 wants −100, but the floor leaves nowhere to go.
        val p = profile(GoalType.LOSE_FAT, sex = Sex.FEMALE, age = 45, heightCm = 155.0, weightKg = 50.0)
        val adj = AdaptiveTdee.weeklyAdjustment(p, 1083, trend(-0.05, latestKg = 50.0))
        assertEquals(AdjustmentDirection.HOLD, adj.direction)
        assertEquals(AdjustmentReason.AT_FLOOR, adj.reason)
        assertEquals(0, adj.deltaKcal)
        assertEquals(1083, adj.suggestedTargetKcal)
        assertTrue(adj.floorApplied)
    }

    @Test
    fun `decrease is partially clamped by the bmr floor`() {
        // Same profile, target 1133: the −100 step would land on 1033, below
        // the 1083 floor → suggested 1083, an actual delta of only −50.
        val p = profile(GoalType.LOSE_FAT, sex = Sex.FEMALE, age = 45, heightCm = 155.0, weightKg = 50.0)
        val adj = AdaptiveTdee.weeklyAdjustment(p, 1133, trend(-0.05, latestKg = 50.0))
        assertEquals(AdjustmentDirection.DECREASE, adj.direction)
        assertEquals(AdjustmentReason.OFF_TARGET, adj.reason)
        assertEquals(-50, adj.deltaKcal)
        assertEquals(1083, adj.suggestedTargetKcal)
        assertTrue(adj.floorApplied)
    }

    @Test
    fun `no rate means insufficient data`() {
        val adj = AdaptiveTdee.weeklyAdjustment(profile(GoalType.LOSE_FAT), 2319, trend(null))
        assertEquals(AdjustmentDirection.HOLD, adj.direction)
        assertEquals(AdjustmentReason.INSUFFICIENT_DATA, adj.reason)
        assertEquals(0, adj.deltaKcal)
        assertNull(adj.deviationKgPerWeek)
    }

    @Test
    fun `a trend spanning under a week is insufficient data`() {
        // A rate exists (two weigh-ins) but only 5 days of span: too little to judge a week by.
        val adj = AdaptiveTdee.weeklyAdjustment(profile(GoalType.LOSE_FAT), 2319, trend(-0.3, spanDays = 5))
        assertEquals(AdjustmentReason.INSUFFICIENT_DATA, adj.reason)
        assertEquals(0, adj.deltaKcal)
    }
}
