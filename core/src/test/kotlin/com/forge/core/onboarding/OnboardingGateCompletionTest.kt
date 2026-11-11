package com.forge.core.onboarding

import com.forge.core.model.ActivityLevel
import com.forge.core.model.ClearanceState
import com.forge.core.model.CoachProfile
import com.forge.core.model.EquipmentAccess
import com.forge.core.model.ExperienceLevel
import com.forge.core.model.GoalProfile
import com.forge.core.model.GoalType
import com.forge.core.model.HealthProfile
import com.forge.core.model.Sex
import com.forge.core.model.UserPreferences
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The full onboarding-completion gate (blueprint 3.0, expanded 2026-11-11):
 * health screen + completed diet screen + named coach, all required before
 * plan generation. The health-only behaviour is covered by [OnboardingGateTest].
 */
class OnboardingGateCompletionTest {

    private val cleanHealth = HealthProfile(onboardingComplete = true)

    private fun goal(dietScreenCompleted: Boolean) = GoalProfile(
        goal = GoalType.BUILD_MUSCLE,
        ageYears = 30,
        sex = Sex.MALE,
        heightCm = 180.0,
        weightKg = 80.0,
        trainingDaysPerWeek = 4,
        experienceLevel = ExperienceLevel.INTERMEDIATE,
        equipmentAccess = EquipmentAccess.FULL_GYM,
        activityLevel = ActivityLevel.MODERATE,
        dietScreenCompleted = dietScreenCompleted,
    )

    private val namedCoach = UserPreferences.defaults().copy(coach = CoachProfile(name = "Forge"))
    private val unnamedCoach = UserPreferences.defaults()

    @Test
    fun `passes when health is complete, diet screen completed, and coach named`() {
        val result = OnboardingGate.evaluateCompletion(goal(dietScreenCompleted = true), cleanHealth, namedCoach)
        assertTrue(result.canGeneratePlans)
        assertTrue(result.dietScreenComplete)
        assertTrue(result.coachConfigured)
    }

    @Test
    fun `completed diet screen passes even when every diet answer is none`() {
        // goal() has empty allergies/dislikes/likedCuisines — "none" everywhere.
        // Completion is the explicit flag, not the presence of answers.
        val result = OnboardingGate.evaluateCompletion(goal(dietScreenCompleted = true), cleanHealth, namedCoach)
        assertTrue(result.canGeneratePlans)
    }

    @Test
    fun `blocks when the diet screen was never completed, however empty the answers`() {
        val result = OnboardingGate.evaluateCompletion(goal(dietScreenCompleted = false), cleanHealth, namedCoach)
        assertFalse(result.canGeneratePlans)
        assertFalse(result.dietScreenComplete)
        assertTrue(result.coachConfigured)
    }

    @Test
    fun `blocks when coach setup is missing`() {
        val result = OnboardingGate.evaluateCompletion(goal(dietScreenCompleted = true), cleanHealth, unnamedCoach)
        assertFalse(result.canGeneratePlans)
        assertFalse(result.coachConfigured)
        assertTrue(result.dietScreenComplete)
    }

    @Test
    fun `red flag still blocks the full gate when diet and coach are complete`() {
        val health = cleanHealth.copy(
            hasDiagnosedHeartCondition = true,
            clearanceState = ClearanceState.REQUIRED,
        )
        val result = OnboardingGate.evaluateCompletion(goal(dietScreenCompleted = true), health, namedCoach)
        assertTrue(result.healthGate.planGenerationBlocked)
        assertFalse(result.canGeneratePlans)
    }

    @Test
    fun `recorded clearance lets the full gate pass`() {
        val health = cleanHealth.copy(
            hasDiagnosedHeartCondition = true,
            clearanceState = ClearanceState.OBTAINED,
        )
        val result = OnboardingGate.evaluateCompletion(goal(dietScreenCompleted = true), health, namedCoach)
        assertTrue(result.canGeneratePlans)
    }

    @Test
    fun `incomplete health onboarding still blocks the full gate`() {
        val health = HealthProfile(onboardingComplete = false)
        val result = OnboardingGate.evaluateCompletion(goal(dietScreenCompleted = true), health, namedCoach)
        assertFalse(result.canGeneratePlans)
    }
}
