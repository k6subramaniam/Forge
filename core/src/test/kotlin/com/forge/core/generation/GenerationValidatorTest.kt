package com.forge.core.generation

import com.forge.core.model.ActivityLevel
import com.forge.core.model.ClearanceState
import com.forge.core.model.EquipmentAccess
import com.forge.core.model.ExperienceLevel
import com.forge.core.model.GoalProfile
import com.forge.core.model.GoalType
import com.forge.core.model.HealthProfile
import com.forge.core.model.JointArea
import com.forge.core.model.Sex
import com.forge.core.nutrition.MacroTargets
import com.forge.core.onboarding.ExerciseExclusion
import com.forge.core.onboarding.FunctionalConstraints
import com.forge.core.onboarding.OnboardingGate
import com.forge.core.onboarding.OnboardingGateResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Generation safety validation (blueprint 3.9): generated workouts are
 * re-vetted against the onboarding exclusions with the session engine's own
 * matching semantics, generated meal plans against the allergy list, the
 * dietary pattern, and the formula-set calorie target.
 */
class GenerationValidatorTest {

    // -- Fixtures -----------------------------------------------------------

    private fun gate(profile: HealthProfile): OnboardingGateResult = OnboardingGate.evaluate(profile)

    private val cleanGate = gate(HealthProfile(onboardingComplete = true))

    private val lowerBackGate = gate(
        HealthProfile(jointLimitations = setOf(JointArea.LOWER_BACK), onboardingComplete = true),
    )

    /** HR medication: HR cap 85% + scale-down-only, but NOT red-flag blocked. */
    private val cardioGate = gate(
        HealthProfile(takesHeartRateAffectingMedication = true, onboardingComplete = true),
    )

    private val blockedGate = gate(
        HealthProfile(
            hasDiagnosedHeartCondition = true,
            clearanceState = ClearanceState.REQUIRED,
            onboardingComplete = true,
        ),
    )

    private fun goalProfile(
        allergies: List<String> = emptyList(),
        dietaryPattern: String? = null,
        dislikes: List<String> = emptyList(),
    ) = GoalProfile(
        goal = GoalType.BUILD_MUSCLE,
        ageYears = 30,
        sex = Sex.MALE,
        heightCm = 180.0,
        weightKg = 80.0,
        trainingDaysPerWeek = 4,
        experienceLevel = ExperienceLevel.INTERMEDIATE,
        equipmentAccess = EquipmentAccess.FULL_GYM,
        activityLevel = ActivityLevel.MODERATE,
        dietaryPattern = dietaryPattern,
        dislikes = dislikes,
        allergies = allergies,
        dietScreenCompleted = true,
    )

    private val targets = MacroTargets(
        caloriesKcal = 2000, proteinGrams = 150, fatGrams = 70, carbGrams = 250, fibreGrams = 30,
    )

    private fun exercise(
        name: String,
        exerciseId: String? = null,
        heartRateCeilingPercentOfMax: Int? = null,
        intensityDirective: IntensityDirective = IntensityDirective.MAINTAIN,
    ) = GeneratedExercise(
        name = name,
        exerciseId = exerciseId,
        sets = 4,
        repRange = 6..8,
        targetWeightKg = 60.0,
        heartRateCeilingPercentOfMax = heartRateCeilingPercentOfMax,
        intensityDirective = intensityDirective,
    )

    private fun workoutPlan(
        exercises: List<GeneratedExercise>,
        routineDirective: IntensityDirective = IntensityDirective.MAINTAIN,
    ) = GeneratedWorkoutPlan(
        name = "Generated Program",
        routines = listOf(
            GeneratedWorkoutRoutine(
                name = "Day 1",
                exercises = exercises,
                intensityDirective = routineDirective,
            ),
        ),
    )

    private fun mealPlanOf(vararg items: GeneratedMealItem, declaredCaloriesKcal: Int? = null) =
        GeneratedMealPlan(
            name = "Generated Meal Plan",
            days = listOf(
                GeneratedMealDay(
                    label = "Day 1",
                    meals = listOf(GeneratedMeal("Lunch", items.toList())),
                    declaredCaloriesKcal = declaredCaloriesKcal,
                ),
            ),
        )

    private fun invalid(result: GenerationValidationResult): GenerationValidationResult.Invalid {
        assertTrue(result is GenerationValidationResult.Invalid, "expected Invalid, was $result")
        return result as GenerationValidationResult.Invalid
    }

    // -- Workouts -----------------------------------------------------------

    @Test
    fun `a clean generated workout passes`() {
        val plan = workoutPlan(
            listOf(
                exercise("Bench Press", exerciseId = "ex-bench-press"),
                exercise("Chest-Supported Row", exerciseId = "ex-chest-supported-row"),
            ),
        )
        val result = GenerationValidator.validateWorkout(plan, lowerBackGate)
        assertTrue(result is GenerationValidationResult.Valid, "was $result")
        assertTrue((result as GenerationValidationResult.Valid).warnings.isEmpty())
    }

    @Test
    fun `a tag-excluded exercise is caught with the trait-based reason`() {
        val plan = workoutPlan(listOf(exercise("Barbell Row", exerciseId = "ex-barbell-row")))
        val result = invalid(GenerationValidator.validateWorkout(plan, lowerBackGate))
        val violation = result.violations.single()
        assertEquals(ValidationRule.EXCLUDED_EXERCISE, violation.rule)
        assertEquals("Barbell Row", violation.subject)
        // The UNSUPPORTED_ROW trait decided it — the same reason the session engine gives.
        assertTrue(violation.reason.contains("no unsupported rowing"), violation.reason)
        assertTrue(violation.correction!!.contains("Chest-Supported Row"), violation.correction)
    }

    @Test
    fun `the name fallback catches an exercise whose exclusion has no structured form`() {
        // An exclusion expressed only as plain-language movement names (the
        // pre-tag form): tags cannot match it, so the name fallback decides.
        val nameOnlyGate = OnboardingGateResult(
            redFlagsPresent = emptyList(),
            planGenerationBlocked = false,
            canGeneratePlans = true,
            constraints = FunctionalConstraints(
                exerciseExclusions = listOf(
                    ExerciseExclusion(
                        area = JointArea.SHOULDER,
                        excludedMovements = listOf("Overhead press"),
                        substitutions = listOf("Landmine press"),
                        reason = "Test shoulder limitation.",
                        restrictions = emptyList(),
                    ),
                ),
                heartRateCapPercentOfMax = null,
                intensityMayOnlyScaleDown = false,
                promptConstraints = emptyList(),
            ),
        )
        val plan = workoutPlan(listOf(exercise("Overhead Press", exerciseId = "ex-overhead-press")))
        val result = invalid(GenerationValidator.validateWorkout(plan, nameOnlyGate))
        val violation = result.violations.single()
        assertEquals(ValidationRule.EXCLUDED_EXERCISE, violation.rule)
        assertTrue(violation.reason.contains("'Overhead press'"), violation.reason)
    }

    @Test
    fun `an exercise that cannot be resolved against the library is flagged`() {
        val plan = workoutPlan(listOf(exercise("Moon Squat")))
        val result = invalid(GenerationValidator.validateWorkout(plan, cleanGate))
        val violation = result.violations.single()
        assertEquals(ValidationRule.UNKNOWN_EXERCISE, violation.rule)
        assertEquals("Moon Squat", violation.subject)
    }

    @Test
    fun `an intensity ceiling above the HR cap is a violation`() {
        val plan = workoutPlan(
            listOf(exercise("Bench Press", exerciseId = "ex-bench-press", heartRateCeilingPercentOfMax = 95)),
        )
        val result = invalid(GenerationValidator.validateWorkout(plan, cardioGate))
        val violation = result.violations.single()
        assertEquals(ValidationRule.HEART_RATE_CAP_EXCEEDED, violation.rule)
        assertTrue(violation.reason.contains("85%"), violation.reason)
    }

    @Test
    fun `an intensity increase for a scale-down-only profile is a violation`() {
        val plan = workoutPlan(
            listOf(exercise("Bench Press", exerciseId = "ex-bench-press")),
            routineDirective = IntensityDirective.INCREASE,
        )
        val result = invalid(GenerationValidator.validateWorkout(plan, cardioGate))
        val violation = result.violations.single()
        assertEquals(ValidationRule.SCALE_DOWN_ONLY_VIOLATION, violation.rule)
    }

    @Test
    fun `a red-flag blocked gate refuses generation outright`() {
        val plan = workoutPlan(listOf(exercise("Barbell Row", exerciseId = "ex-barbell-row")))
        val result = GenerationValidator.validateWorkout(plan, blockedGate)
        assertTrue(result is GenerationValidationResult.Refused, "was $result")
        val refused = result as GenerationValidationResult.Refused
        assertEquals(listOf("Diagnosed heart condition"), refused.redFlagsPresent)
        assertTrue(
            GenerationValidator.regenerationNote(result).startsWith("Do not regenerate"),
        )
    }

    // -- Meal plans -----------------------------------------------------------

    @Test
    fun `a clean generated meal plan passes`() {
        val plan = mealPlanOf(
            GeneratedMealItem(
                "Grilled Chicken Bowl",
                ingredients = listOf("chicken breast", "rice", "broccoli"),
                caloriesKcal = 1200,
            ),
            GeneratedMealItem(
                "Greek Yogurt and Berries",
                ingredients = listOf("greek yogurt", "strawberries", "honey"),
                caloriesKcal = 800,
            ),
        )
        val result = GenerationValidator.validateMealPlan(plan, goalProfile(), targets)
        assertTrue(result is GenerationValidationResult.Valid, "was $result")
        assertTrue((result as GenerationValidationResult.Valid).warnings.isEmpty())
    }

    @Test
    fun `allergens are caught across case, plurals, and ingredient lists`() {
        val plan = mealPlanOf(
            GeneratedMealItem(
                "Peanut Noodles",
                ingredients = listOf("rice noodles", "Peanut butter", "soy sauce"),
            ),
            GeneratedMealItem(
                "Breakfast Wrap",
                ingredients = listOf("flour tortilla", "SCRAMBLED EGGS", "cheese"),
            ),
            declaredCaloriesKcal = 2000,
        )
        val profile = goalProfile(allergies = listOf("peanuts", "Egg"))
        val result = invalid(GenerationValidator.validateMealPlan(plan, profile, targets))
        assertEquals(2, result.violations.size)
        assertTrue(result.violations.all { it.rule == ValidationRule.ALLERGEN_PRESENT })
        assertTrue(result.violations.any { it.subject == "Peanut Noodles" })
        assertTrue(result.violations.any { it.subject == "Breakfast Wrap" })
    }

    @Test
    fun `whole-word matching does not trip an allergen on a longer word`() {
        val plan = mealPlanOf(
            GeneratedMealItem(
                "Eggplant Parmesan",
                ingredients = listOf("eggplant", "tomato", "olive oil"),
            ),
            declaredCaloriesKcal = 2000,
        )
        val result = GenerationValidator.validateMealPlan(
            plan, goalProfile(allergies = listOf("egg")), targets,
        )
        assertTrue(result is GenerationValidationResult.Valid, "was $result")
    }

    @Test
    fun `a dietary pattern violation is caught`() {
        val plan = mealPlanOf(
            GeneratedMealItem(
                "Grilled Chicken Salad",
                ingredients = listOf("chicken breast", "lettuce", "olive oil"),
            ),
            declaredCaloriesKcal = 2000,
        )
        val profile = goalProfile(dietaryPattern = "vegetarian")
        val result = invalid(GenerationValidator.validateMealPlan(plan, profile, targets))
        val violation = result.violations.single()
        assertEquals(ValidationRule.DIETARY_PATTERN_VIOLATION, violation.rule)
        assertTrue(violation.reason.contains("vegetarian"), violation.reason)
    }

    @Test
    fun `a disliked food is a warning only and the plan stays valid`() {
        val plan = mealPlanOf(
            GeneratedMealItem(
                "Rice Bowl",
                ingredients = listOf("rice", "black beans", "cilantro"),
            ),
            declaredCaloriesKcal = 2000,
        )
        val profile = goalProfile(dislikes = listOf("cilantro"))
        val result = GenerationValidator.validateMealPlan(plan, profile, targets)
        assertTrue(result is GenerationValidationResult.Valid, "was $result")
        val warnings = (result as GenerationValidationResult.Valid).warnings
        assertEquals(1, warnings.size)
        assertEquals(ValidationRule.DISLIKED_FOOD, warnings.single().rule)
    }

    @Test
    fun `an item without ingredients lands in the unverifiable warning state`() {
        val plan = mealPlanOf(
            GeneratedMealItem("Garden Salad", ingredients = null, caloriesKcal = 2000),
        )
        val result = GenerationValidator.validateMealPlan(
            plan, goalProfile(allergies = listOf("peanut")), targets,
        )
        assertTrue(result is GenerationValidationResult.Valid, "was $result")
        val warnings = (result as GenerationValidationResult.Valid).warnings
        assertEquals(1, warnings.size)
        assertEquals(ValidationRule.UNVERIFIABLE_INGREDIENTS, warnings.single().rule)
    }

    @Test
    fun `a day wildly off the calorie target is a violation, and the band edge passes`() {
        val offTarget = mealPlanOf(
            GeneratedMealItem("Light Soup", ingredients = listOf("vegetable broth", "carrots")),
            declaredCaloriesKcal = 1200,
        )
        val result = invalid(GenerationValidator.validateMealPlan(offTarget, goalProfile(), targets))
        val violation = result.violations.single()
        assertEquals(ValidationRule.CALORIES_OUT_OF_RANGE, violation.rule)
        assertTrue(violation.reason.contains("2000 kcal"), violation.reason)

        val atBandEdge = mealPlanOf(
            GeneratedMealItem("Light Soup", ingredients = listOf("vegetable broth", "carrots")),
            declaredCaloriesKcal = 1800, // exactly −10% of the 2000 kcal target
        )
        val edgeResult = GenerationValidator.validateMealPlan(atBandEdge, goalProfile(), targets)
        assertTrue(edgeResult is GenerationValidationResult.Valid, "was $edgeResult")
    }

    // -- Regeneration guidance --------------------------------------------------

    @Test
    fun `the correction note names the offending exercises and their reasons`() {
        val plan = workoutPlan(listOf(exercise("Barbell Row", exerciseId = "ex-barbell-row")))
        val result = GenerationValidator.validateWorkout(plan, lowerBackGate)
        val note = GenerationValidator.regenerationNote(result)
        assertTrue(note.contains("Regenerate"), note)
        assertTrue(note.contains("Barbell Row"), note)
        assertTrue(note.contains("no unsupported rowing"), note)
    }

    @Test
    fun `the correction note names the offending foods and their allergens`() {
        val plan = mealPlanOf(
            GeneratedMealItem(
                "Peanut Noodles",
                ingredients = listOf("rice noodles", "peanut butter"),
            ),
            declaredCaloriesKcal = 2000,
        )
        val result = GenerationValidator.validateMealPlan(
            plan, goalProfile(allergies = listOf("peanut")), targets,
        )
        val note = GenerationValidator.regenerationNote(result)
        assertTrue(note.contains("Peanut Noodles"), note)
        assertTrue(note.contains("peanut"), note)
    }
}
