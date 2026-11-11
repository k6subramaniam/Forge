package com.forge.core.onboarding

import com.forge.core.model.ClearanceState
import com.forge.core.model.HealthProfile
import com.forge.core.model.JointArea
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OnboardingGateTest {

    private val cleanComplete = HealthProfile(onboardingComplete = true)

    @Test
    fun `clean completed profile can generate plans with no constraints`() {
        val result = OnboardingGate.evaluate(cleanComplete)
        assertTrue(result.canGeneratePlans)
        assertFalse(result.planGenerationBlocked)
        assertTrue(result.redFlagsPresent.isEmpty())
        assertTrue(result.constraints.exerciseExclusions.isEmpty())
        assertNull(result.constraints.heartRateCapPercentOfMax)
        assertFalse(result.constraints.intensityMayOnlyScaleDown)
    }

    @Test
    fun `nothing generates before onboarding is complete, even with a clean screen`() {
        val result = OnboardingGate.evaluate(HealthProfile(onboardingComplete = false))
        assertFalse(result.canGeneratePlans)
        assertFalse(result.planGenerationBlocked) // not a red-flag block; just incomplete
    }

    @Test
    fun `diagnosed heart condition is a red flag and blocks generation`() {
        val profile = cleanComplete.copy(hasDiagnosedHeartCondition = true, clearanceState = ClearanceState.REQUIRED)
        val result = OnboardingGate.evaluate(profile)
        assertEquals(listOf("Diagnosed heart condition"), result.redFlagsPresent)
        assertTrue(result.planGenerationBlocked)
        assertFalse(result.canGeneratePlans)
    }

    @Test
    fun `exertional chest pain is a red flag and blocks generation`() {
        val profile = cleanComplete.copy(hasExertionalChestPain = true, clearanceState = ClearanceState.REQUIRED)
        val result = OnboardingGate.evaluate(profile)
        assertTrue(result.planGenerationBlocked)
        assertFalse(result.canGeneratePlans)
    }

    @Test
    fun `doctor's no-exercise instruction is a red flag and blocks generation`() {
        val profile = cleanComplete.copy(doctorAdvisedAgainstExercise = true, clearanceState = ClearanceState.REQUIRED)
        val result = OnboardingGate.evaluate(profile)
        assertTrue(result.planGenerationBlocked)
        assertFalse(result.canGeneratePlans)
    }

    @Test
    fun `recorded medical clearance lifts the red-flag block`() {
        val profile = cleanComplete.copy(
            hasDiagnosedHeartCondition = true,
            clearanceState = ClearanceState.OBTAINED,
        )
        val result = OnboardingGate.evaluate(profile)
        assertFalse(result.planGenerationBlocked)
        assertTrue(result.canGeneratePlans)
        assertEquals(listOf("Diagnosed heart condition"), result.redFlagsPresent) // history is still reported
    }

    @Test
    fun `knee limitation excludes deep squats and lunges with named substitutions`() {
        val profile = cleanComplete.copy(jointLimitations = setOf(JointArea.KNEE))
        val result = OnboardingGate.evaluate(profile)
        val exclusion = result.constraints.exerciseExclusions.single()
        assertEquals(JointArea.KNEE, exclusion.area)
        assertTrue(exclusion.excludedMovements.any { it.contains("squat", ignoreCase = true) })
        assertTrue(exclusion.excludedMovements.any { it.contains("lunge", ignoreCase = true) })
        assertTrue(exclusion.substitutions.any { it.contains("box squat", ignoreCase = true) })
        assertTrue(result.canGeneratePlans) // limitations constrain plans; they do not block them
    }

    @Test
    fun `shoulder limitation excludes overhead pressing`() {
        val profile = cleanComplete.copy(jointLimitations = setOf(JointArea.SHOULDER))
        val exclusion = OnboardingGate.evaluate(profile).constraints.exerciseExclusions.single()
        assertTrue(exclusion.excludedMovements.any { it.contains("overhead", ignoreCase = true) })
    }

    @Test
    fun `multiple limitations produce one exclusion each`() {
        val profile = cleanComplete.copy(jointLimitations = setOf(JointArea.KNEE, JointArea.LOWER_BACK))
        val exclusions = OnboardingGate.evaluate(profile).constraints.exerciseExclusions
        assertEquals(2, exclusions.size)
        assertEquals(setOf(JointArea.KNEE, JointArea.LOWER_BACK), exclusions.map { it.area }.toSet())
    }

    @Test
    fun `heart-rate medication caps working HR at 85 percent and forbids scaling up`() {
        val profile = cleanComplete.copy(takesHeartRateAffectingMedication = true)
        val constraints = OnboardingGate.evaluate(profile).constraints
        assertEquals(85, constraints.heartRateCapPercentOfMax)
        assertTrue(constraints.intensityMayOnlyScaleDown)
    }

    @Test
    fun `high blood pressure also caps HR without being a hard red flag`() {
        val profile = cleanComplete.copy(hasHighBloodPressure = true)
        val result = OnboardingGate.evaluate(profile)
        assertTrue(result.canGeneratePlans) // hypertension alone is not one of the three red flags
        assertEquals(85, result.constraints.heartRateCapPercentOfMax)
        assertTrue(result.constraints.intensityMayOnlyScaleDown)
    }

    @Test
    fun `asthma adds a breathing prompt constraint but no HR cap`() {
        val profile = cleanComplete.copy(hasAsthma = true)
        val constraints = OnboardingGate.evaluate(profile).constraints
        assertNull(constraints.heartRateCapPercentOfMax)
        assertTrue(constraints.promptConstraints.any { it.contains("Breathing", ignoreCase = true) })
    }

    @Test
    fun `prompt constraints carry functional language, not diagnoses`() {
        val profile = cleanComplete.copy(
            jointLimitations = setOf(JointArea.KNEE),
            takesHeartRateAffectingMedication = true,
            freeTextLimitations = "Left ankle sprain, still rehabbing",
        )
        val prompts = OnboardingGate.evaluate(profile).constraints.promptConstraints
        assertTrue(prompts.isNotEmpty())
        assertTrue(prompts.none { it.contains("diagnosed", ignoreCase = true) })
        assertTrue(prompts.any { it.contains("ankle sprain", ignoreCase = true) })
    }
}
