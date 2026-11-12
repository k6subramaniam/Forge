package com.forge.core.coach

import com.forge.core.data.ExerciseLibrary
import com.forge.core.model.AiMode
import com.forge.core.model.AiSettings
import com.forge.core.model.ClearanceState
import com.forge.core.model.CloudProvider
import com.forge.core.model.CloudQualityFeature
import com.forge.core.model.CoachProfile
import com.forge.core.model.CoachTone
import com.forge.core.model.HealthProfile
import com.forge.core.model.JointArea
import com.forge.core.model.RoutineExercise
import com.forge.core.onboarding.OnboardingGate
import com.forge.core.onboarding.OnboardingGateResult
import com.forge.core.readiness.LoadAdjuster
import com.forge.core.readiness.ReadinessAssessment
import com.forge.core.readiness.ReadinessBand
import com.forge.core.readiness.ReadinessStatus
import com.forge.core.training.NextSessionPrescription
import com.forge.core.training.PrescribedExerciseTarget
import com.forge.core.training.WeightUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class CoachPromptAssemblerTest {

    private val coach = CoachProfile(name = "Atlas")

    private val openGate: OnboardingGateResult =
        OnboardingGate.evaluate(HealthProfile(onboardingComplete = true))

    private fun ready(plan: CoachRequestPlan): AssembledCoachPrompt =
        (plan as CoachRequestPlan.Ready).prompt

    // -- Persona, tone, grounding ------------------------------------------------

    @Test
    fun `prompt carries the persona, the grounding rules and the reply discipline`() {
        val prompt = ready(CoachPromptAssembler.plan(coach, openGate))

        assertEquals("Atlas", prompt.coachName)
        assertEquals(AiMode.ON_DEVICE, prompt.mode)
        assertTrue(prompt.systemPrompt.contains("You are Atlas"), prompt.systemPrompt)
        // Grounding rule (3.15): context numbers only; anything else is labelled opinion.
        assertTrue(prompt.systemPrompt.contains("Never invent a target, threshold, weight, or adjustment"), prompt.systemPrompt)
        assertTrue(prompt.systemPrompt.contains("labelled as your opinion"), prompt.systemPrompt)
        assertTrue(prompt.systemPrompt.contains("not medical advice"), prompt.systemPrompt)
        // P2 reply discipline: fact line first, then exactly one lever.
        assertTrue(prompt.systemPrompt.contains("first line states the key fact"), prompt.systemPrompt)
        assertTrue(prompt.systemPrompt.contains("exactly one lever"), prompt.systemPrompt)
        // No constraints declared on an empty profile.
        assertTrue(prompt.systemPrompt.contains("- None declared."), prompt.systemPrompt)
    }

    @Test
    fun `each tone produces its own instruction line`() {
        fun systemFor(tone: CoachTone) = CoachPromptAssembler.systemPrompt(
            CoachProfile(name = "Atlas", tone = tone),
            openGate.constraints,
        )
        assertTrue(systemFor(CoachTone.SUPPORTIVE).contains("never a guilt trip"))
        assertTrue(systemFor(CoachTone.DRILL_SERGEANT).contains("Drill-sergeant: terse, direct, demanding"))
        assertTrue(systemFor(CoachTone.STRAIGHT_DATA).contains("numbers and facts only"))
    }

    @Test
    fun `gate constraints enter the prompt verbatim`() {
        val gate = OnboardingGate.evaluate(
            HealthProfile(jointLimitations = setOf(JointArea.KNEE), onboardingComplete = true),
        )
        val prompt = ready(CoachPromptAssembler.plan(coach, gate))

        assertTrue(gate.constraints.promptConstraints.isNotEmpty())
        for (constraint in gate.constraints.promptConstraints) {
            assertTrue(prompt.systemPrompt.contains(constraint), "missing verbatim constraint: $constraint")
        }
        assertTrue(prompt.systemPrompt.contains("Avoid deep squat"), prompt.systemPrompt)
    }

    // -- Privacy -------------------------------------------------------------------

    @Test
    fun `diagnoses and medication details never reach the prompt - functional statements do`() {
        val profile = HealthProfile(
            hasHighBloodPressure = true,
            takesHeartRateAffectingMedication = true,
            hasDiabetes = true,
            jointLimitations = setOf(JointArea.KNEE),
            onboardingComplete = true,
        )
        val gate = OnboardingGate.evaluate(profile)
        assertFalse(gate.planGenerationBlocked)

        val readiness = ReadinessAssessment(
            date = LocalDate.of(2026, 11, 11),
            status = ReadinessStatus.SCORED,
            band = ReadinessBand.STRAINED,
            score = 38,
            compositeZ = -0.9,
            signals = emptyList(),
            missingSignals = emptyList(),
            baselineDaysUsed = 28,
            headline = "Strained — readiness is below your baseline.",
        )
        val target = PrescribedExerciseTarget(
            prescription = RoutineExercise(ExerciseLibrary.benchPress, 4, 6..8, 60.0, 120),
            suggestedWeightKg = 60.0,
            reason = "Repeat the weight and build reps.",
            ghost = null,
            overload = null,
            substitutedFor = null,
            adjustmentReason = null,
        )
        val context = CoachContexts.morning(
            readiness,
            NextSessionPrescription("routine-push-a", "Push A", listOf(target), emptyList()),
            LoadAdjuster.recommend(readiness, gate.constraints),
        )

        val prompt = ready(CoachPromptAssembler.plan(coach, gate, context))
        val text = prompt.fullText.lowercase()

        // The functional statements the gate sanctions DO cross the boundary.
        assertTrue(text.contains("85% of max"), text)
        assertTrue(text.contains("scale intensity down only"), text)
        assertTrue(text.contains("avoid deep squat"), text)
        // The diagnosis and medication words behind them never do.
        for (banned in listOf("hypertension", "blood pressure", "beta blocker", "beta-blocker", "diabetes", "medication", "diagnosed")) {
            assertFalse(text.contains(banned), "prompt leaked '$banned':\n$text")
        }
    }

    // -- Red-flag refusal ------------------------------------------------------------

    @Test
    fun `blocked profile gets a refusal with no plan content`() {
        val gate = OnboardingGate.evaluate(
            HealthProfile(
                hasDiagnosedHeartCondition = true,
                onboardingComplete = true,
                clearanceState = ClearanceState.REQUIRED,
            ),
        )
        assertTrue(gate.planGenerationBlocked)

        val plan = CoachPromptAssembler.plan(coach, gate, context = null)
        val refusal = plan as CoachRequestPlan.Refused
        assertTrue(refusal.message.contains("medical clearance"), refusal.message)
        assertEquals(listOf("Diagnosed heart condition"), refusal.redFlagsPresent)
        // The refusal is the whole output — no prescription content rides along.
        assertFalse(refusal.message.contains("Bench Press"), refusal.message)
    }

    @Test
    fun `blocked beats cloud gating - safety state first`() {
        val gate = OnboardingGate.evaluate(
            HealthProfile(
                hasExertionalChestPain = true,
                onboardingComplete = true,
                clearanceState = ClearanceState.REQUIRED,
            ),
        )
        val plan = CoachPromptAssembler.plan(
            coach, gate,
            aiSettings = AiSettings(),
            feature = CloudQualityFeature.FULL_PROGRAM_GENERATION,
        )
        assertTrue(plan is CoachRequestPlan.Refused, "expected Refused, got $plan")
    }

    // -- Mode awareness and cloud gating ------------------------------------------------

    @Test
    fun `cloud-quality feature without configured cloud surfaces the opt-in state`() {
        val plan = CoachPromptAssembler.plan(
            coach, openGate,
            aiSettings = AiSettings(),
            feature = CloudQualityFeature.FULL_PROGRAM_GENERATION,
        )
        val optIn = plan as CoachRequestPlan.NeedsCloudOptIn
        assertEquals(CloudQualityFeature.FULL_PROGRAM_GENERATION, optIn.feature)
        assertTrue(optIn.message.contains("API key"), optIn.message)
    }

    @Test
    fun `cloud selected but key missing still surfaces the opt-in state`() {
        val settings = AiSettings(mode = AiMode.CLOUD, cloudProvider = CloudProvider.GEMINI, cloudApiKeyConfigured = false)
        val plan = CoachPromptAssembler.plan(
            coach, openGate,
            aiSettings = settings,
            feature = CloudQualityFeature.PHOTO_MEAL_ANALYSIS,
        )
        assertTrue(plan is CoachRequestPlan.NeedsCloudOptIn, "expected NeedsCloudOptIn, got $plan")
    }

    @Test
    fun `cloud-quality feature runs in cloud mode once cloud is configured`() {
        val settings = AiSettings(mode = AiMode.CLOUD, cloudProvider = CloudProvider.CLAUDE, cloudApiKeyConfigured = true)
        val prompt = ready(
            CoachPromptAssembler.plan(
                coach, openGate,
                aiSettings = settings,
                feature = CloudQualityFeature.FULL_PROGRAM_GENERATION,
            ),
        )
        assertEquals(AiMode.CLOUD, prompt.mode)
    }

    @Test
    fun `ordinary features run on-device by default`() {
        val prompt = ready(
            CoachPromptAssembler.plan(
                coach, openGate,
                aiSettings = AiSettings(),
                feature = CloudQualityFeature.MORNING_BRIEF,
            ),
        )
        assertEquals(AiMode.ON_DEVICE, prompt.mode)
    }

    // -- Context rendering through the assembler ------------------------------------------

    @Test
    fun `context renders in the requested unit through the assembler`() {
        val readiness = ReadinessAssessment(
            date = LocalDate.of(2026, 11, 11),
            status = ReadinessStatus.SCORED,
            band = ReadinessBand.NORMAL,
            score = 52,
            compositeZ = 0.1,
            signals = emptyList(),
            missingSignals = emptyList(),
            baselineDaysUsed = 28,
            headline = "Normal readiness — in line with your baseline.",
        )
        val target = PrescribedExerciseTarget(
            prescription = RoutineExercise(ExerciseLibrary.benchPress, 4, 6..8, 60.0, 120),
            suggestedWeightKg = 60.0,
            reason = "Repeat the weight and build reps.",
            ghost = null,
            overload = null,
            substitutedFor = null,
            adjustmentReason = null,
        )
        val context = CoachContexts.morning(
            readiness,
            NextSessionPrescription("routine-push-a", "Push A", listOf(target), emptyList()),
            LoadAdjuster.recommend(readiness, openGate.constraints),
        )

        val lb = ready(CoachPromptAssembler.plan(coach, openGate, context))
        assertTrue(lb.fullText.contains("132.3 lb"), lb.fullText)
        val kg = ready(CoachPromptAssembler.plan(coach, openGate, context, unit = WeightUnit.KG))
        assertTrue(kg.fullText.contains("60 kg"), kg.fullText)
        assertFalse(kg.fullText.contains("132.3"), kg.fullText)
    }
}
