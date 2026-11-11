package com.forge.core.nutrition

import com.forge.core.model.ActivityLevel
import com.forge.core.model.EquipmentAccess
import com.forge.core.model.ExperienceLevel
import com.forge.core.model.GoalProfile
import com.forge.core.model.GoalType
import com.forge.core.model.Sex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class EnergyTargetsTest {

    private fun profile(
        goal: GoalType = GoalType.MAINTAIN,
        sex: Sex = Sex.MALE,
        age: Int = 30,
        heightCm: Double = 180.0,
        weightKg: Double = 80.0,
        activity: ActivityLevel = ActivityLevel.MODERATE,
        trainDays: Int = 4,
    ) = GoalProfile(
        goal = goal,
        ageYears = age,
        sex = sex,
        heightCm = heightCm,
        weightKg = weightKg,
        trainingDaysPerWeek = trainDays,
        experienceLevel = ExperienceLevel.INTERMEDIATE,
        equipmentAccess = EquipmentAccess.FULL_GYM,
        activityLevel = activity,
    )

    // -- BMR (Mifflin-St Jeor) ---------------------------------------------------

    @Test
    fun `bmr for a man`() {
        // 10×80 + 6.25×180 − 5×30 + 5 = 800 + 1125 − 150 + 5 = 1780
        assertEquals(1780.0, EnergyTargets.bmr(80.0, 180.0, 30, Sex.MALE), 0.001)
    }

    @Test
    fun `bmr for a woman`() {
        // 10×65 + 6.25×165 − 5×40 − 161 = 650 + 1031.25 − 200 − 161 = 1320.25
        assertEquals(1320.25, EnergyTargets.bmr(65.0, 165.0, 40, Sex.FEMALE), 0.001)
    }

    // -- TDEE ---------------------------------------------------------------------

    @Test
    fun `activity multipliers are the standard scale`() {
        assertEquals(1.2, EnergyTargets.activityMultiplier(ActivityLevel.SEDENTARY), 0.0)
        assertEquals(1.375, EnergyTargets.activityMultiplier(ActivityLevel.LIGHT), 0.0)
        assertEquals(1.55, EnergyTargets.activityMultiplier(ActivityLevel.MODERATE), 0.0)
        assertEquals(1.725, EnergyTargets.activityMultiplier(ActivityLevel.HIGH), 0.0)
        assertEquals(1.9, EnergyTargets.activityMultiplier(ActivityLevel.ATHLETE), 0.0)
    }

    @Test
    fun `formula tdee is bmr times activity`() {
        // 1780 × 1.55 = 2759
        assertEquals(2759.0, EnergyTargets.formulaTdee(profile()), 0.001)
    }

    // -- Calorie target by goal -----------------------------------------------------

    @Test
    fun `build muscle adds a lean surplus`() {
        // Rate: +0.25% of 80 kg = +0.2 kg/week → 0.2 × 7700 / 7 = +220 kcal/day
        val target = EnergyTargets.calorieTarget(profile(goal = GoalType.BUILD_MUSCLE), maintenanceKcal = 2759.0)
        assertEquals(2979, target.caloriesKcal)
        assertEquals(220, target.adjustmentKcal)
        assertEquals(0.2, target.targetRateKgPerWeek, 0.001)
        assertFalse(target.floorApplied)
    }

    @Test
    fun `lose fat subtracts the deficit`() {
        // Rate: −0.5% of 80 kg = −0.4 kg/week → −0.4 × 7700 / 7 = −440 kcal/day
        val target = EnergyTargets.calorieTarget(profile(goal = GoalType.LOSE_FAT), maintenanceKcal = 2759.0)
        assertEquals(2319, target.caloriesKcal)
        assertEquals(-440, target.adjustmentKcal)
        assertFalse(target.floorApplied)
    }

    @Test
    fun `maintain and recomp target maintenance calories`() {
        assertEquals(2759, EnergyTargets.calorieTarget(profile(goal = GoalType.MAINTAIN), 2759.0).caloriesKcal)
        assertEquals(2759, EnergyTargets.calorieTarget(profile(goal = GoalType.RECOMP), 2759.0).caloriesKcal)
    }

    @Test
    fun `deficit target is floored at bmr`() {
        // Woman, 50 kg, 155 cm, 45 y, sedentary:
        // BMR = 500 + 968.75 − 225 − 161 = 1082.75; TDEE = 1082.75 × 1.2 = 1299.3
        // Deficit: −0.5% of 50 = −0.25 kg/week → −275 kcal → raw target 1024.3 < BMR
        // → floored to ceil(1082.75) = 1083; adjustment = 1083 − 1299 = −216
        val p = profile(
            goal = GoalType.LOSE_FAT, sex = Sex.FEMALE, age = 45,
            heightCm = 155.0, weightKg = 50.0, activity = ActivityLevel.SEDENTARY,
        )
        val target = EnergyTargets.calorieTarget(p, EnergyTargets.formulaTdee(p))
        assertEquals(1083, target.caloriesKcal)
        assertEquals(-216, target.adjustmentKcal)
        assertTrue(target.floorApplied)
    }

    // -- Macros ---------------------------------------------------------------------

    @Test
    fun `protein dose by goal stays in the evidence band`() {
        assertEquals(1.6, EnergyTargets.proteinGramsPerKg(GoalType.MAINTAIN), 0.0)
        assertEquals(1.8, EnergyTargets.proteinGramsPerKg(GoalType.BUILD_MUSCLE), 0.0)
        assertEquals(2.0, EnergyTargets.proteinGramsPerKg(GoalType.RECOMP), 0.0)
        assertEquals(2.2, EnergyTargets.proteinGramsPerKg(GoalType.LOSE_FAT), 0.0)
    }

    @Test
    fun `macro split sums back to the calorie target`() {
        // 2760 kcal, 80 kg, MAINTAIN:
        // protein 1.6 × 80 = 128 g (512 kcal)
        // fat: 25% of 2760 = 690 kcal → 76.7 g, above the 0.5×80 = 40 g floor → 77 g (693 kcal)
        // carbs: (2760 − 512 − 693) / 4 = 388.75 → 389 g; fibre: 2760 × 14/1000 = 38.64 → 39 g
        val macros = EnergyTargets.macroTargets(2760, 80.0, GoalType.MAINTAIN)
        assertEquals(128, macros.proteinGrams)
        assertEquals(77, macros.fatGrams)
        assertEquals(389, macros.carbGrams)
        assertEquals(39, macros.fibreGrams)
        val implied = macros.proteinGrams * 4 + macros.fatGrams * 9 + macros.carbGrams * 4
        assertTrue(abs(implied - 2760) <= 5, "split sums to $implied, target 2760")
    }

    @Test
    fun `fat floor binds at low calorie targets`() {
        // 1300 kcal, 90 kg, LOSE_FAT:
        // protein 2.2 × 90 = 198 g (792 kcal)
        // fat share 25% → 325 kcal → 36 g, but the floor is 0.5 × 90 = 45 g (405 kcal) → floor wins
        // carbs: (1300 − 792 − 405) / 4 = 25.75 → 26 g; fibre: 1300 × 14/1000 = 18.2 → 18 g
        val macros = EnergyTargets.macroTargets(1300, 90.0, GoalType.LOSE_FAT)
        assertEquals(198, macros.proteinGrams)
        assertEquals(45, macros.fatGrams)
        assertEquals(26, macros.carbGrams)
        assertEquals(18, macros.fibreGrams)
    }

    // -- Training / rest day split -----------------------------------------------------

    @Test
    fun `protein is capped to fit the calories when the full dose cannot`() {
        // Codex P2 (#4): 70-year-old woman, 180 kg, 150 cm, sedentary, fat loss.
        // BMR = 1800 + 937.5 − 350 − 161 = 2226.5 → floored target 2,227 kcal.
        // Dosed protein 2.2 × 180 = 396 g (1,584 kcal) + fat floor 90 g (810 kcal)
        // = 2,394 kcal > 2,227. Capped protein = (2,227 − 810) / 4 = 354 g.
        val heavy = profile(
            goal = GoalType.LOSE_FAT, sex = Sex.FEMALE, age = 70,
            heightCm = 150.0, weightKg = 180.0, activity = ActivityLevel.SEDENTARY, trainDays = 3,
        )
        val plan = EnergyTargets.dailyTargets(heavy, EnergyTargets.formulaTdee(heavy))
        listOf(plan.averageDay, plan.trainingDay, plan.restDay).forEach { day ->
            assertTrue(day.proteinCappedToFit)
            assertEquals(90, day.fatGrams)
            val kcal = day.proteinGrams * 4 + day.fatGrams * 9 + day.carbGrams * 4
            assertTrue(kcal <= day.caloriesKcal + 2, "macros $kcal kcal exceed the ${day.caloriesKcal} target")
        }
        assertEquals(2227, plan.averageDay.caloriesKcal)
        assertEquals(354, plan.averageDay.proteinGrams)
        assertEquals(0, plan.averageDay.carbGrams)
        // Protein stays identical across day types.
        assertEquals(plan.averageDay.proteinGrams, plan.restDay.proteinGrams)
        assertEquals(plan.averageDay.proteinGrams, plan.trainingDay.proteinGrams)
    }

    @Test
    fun `macro targets cap protein directly too, and only when needed`() {
        // 150 kg fat loss: dose 330 g (1,320 kcal), fat floor 75 g (675 kcal).
        val roomy = EnergyTargets.macroTargets(2000, 150.0, GoalType.LOSE_FAT)
        assertFalse(roomy.proteinCappedToFit)
        assertEquals(330, roomy.proteinGrams)

        val tight = EnergyTargets.macroTargets(1800, 150.0, GoalType.LOSE_FAT)
        assertTrue(tight.proteinCappedToFit)
        assertEquals((1800 - 75 * 9) / 4, tight.proteinGrams) // 281 g
        assertEquals(0, tight.carbGrams)
    }

    @Test
    fun `training days carry more, rest days balance the week`() {
        // MAINTAIN at 2800 maintenance → average target 2800, 4 training days.
        // Training: 2800 × 1.05 = 2940. Rest: (7×2800 − 4×2940) / 3 = 7840/3 → 2613.
        // Weekly total: 4×2940 + 3×2613 = 19599 ≈ 19600 (rounding only).
        val plan = EnergyTargets.dailyTargets(profile(trainDays = 4), maintenanceKcal = 2800.0)
        assertEquals(2800, plan.averageDay.caloriesKcal)
        assertEquals(2940, plan.trainingDay.caloriesKcal)
        assertEquals(2613, plan.restDay.caloriesKcal)
        assertFalse(plan.daySplitFloorApplied)
        val weekly = 4 * plan.trainingDay.caloriesKcal + 3 * plan.restDay.caloriesKcal
        assertTrue(abs(weekly - 7 * 2800) <= 7, "weekly total $weekly drifts from 19600")
        // Protein and fat identical across day types; the swing is all carbohydrate.
        assertEquals(plan.averageDay.proteinGrams, plan.trainingDay.proteinGrams)
        assertEquals(plan.averageDay.proteinGrams, plan.restDay.proteinGrams)
        assertEquals(plan.averageDay.fatGrams, plan.trainingDay.fatGrams)
        assertEquals(plan.averageDay.fatGrams, plan.restDay.fatGrams)
        assertTrue(plan.trainingDay.carbGrams > plan.restDay.carbGrams)
    }

    @Test
    fun `rest day floor clamps the split and rebalances`() {
        // The floored-deficit profile from above (target = BMR = 1083), 3 training days.
        // Unclamped: training 1083 × 1.05 → 1137; rest (7581 − 3411)/4 ≈ 1043 < 1083 floor
        // → rest clamps to 1083 and training rebalances to (7581 − 4332)/3 = 1083.
        val p = profile(
            goal = GoalType.LOSE_FAT, sex = Sex.FEMALE, age = 45,
            heightCm = 155.0, weightKg = 50.0, activity = ActivityLevel.SEDENTARY, trainDays = 3,
        )
        val plan = EnergyTargets.dailyTargets(p, EnergyTargets.formulaTdee(p))
        assertEquals(1083, plan.target.caloriesKcal)
        assertTrue(plan.daySplitFloorApplied)
        assertEquals(1083, plan.restDay.caloriesKcal)
        assertEquals(1083, plan.trainingDay.caloriesKcal)
    }

    @Test
    fun `no split when every day or no day is a training day`() {
        val seven = EnergyTargets.dailyTargets(profile(trainDays = 7), maintenanceKcal = 2800.0)
        assertEquals(seven.averageDay, seven.trainingDay)
        assertEquals(seven.averageDay, seven.restDay)
        val zero = EnergyTargets.dailyTargets(profile(trainDays = 0), maintenanceKcal = 2800.0)
        assertEquals(zero.averageDay, zero.trainingDay)
        assertEquals(zero.averageDay, zero.restDay)
    }
}
