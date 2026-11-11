package com.forge.core.coach

import com.forge.core.model.AiMode
import com.forge.core.model.AiSettings
import com.forge.core.model.CloudQualityFeature
import com.forge.core.model.CoachProfile
import com.forge.core.model.CoachTone
import com.forge.core.onboarding.FunctionalConstraints
import com.forge.core.onboarding.OnboardingGateResult
import com.forge.core.training.WeightUnit

/**
 * A fully assembled coach request: the system prompt (persona, tone, ground
 * rules, hard constraints) plus the rendered [CoachContext] block. Mode says
 * where the app layer would run it — this slice performs no network calls
 * and reads no keys; it only assembles text and states.
 */
data class AssembledCoachPrompt(
    val coachName: String,
    val tone: CoachTone,
    /** The mode this request runs in, per [AiSettings.effectiveMode]. */
    val mode: AiMode,
    val systemPrompt: String,
    /** The rendered context block, or null when the request carries no summary. */
    val contextBlock: String?,
) {
    /** System prompt and context as one text, for app layers that send a single payload. */
    val fullText: String
        get() = if (contextBlock.isNullOrBlank()) {
            systemPrompt
        } else {
            "$systemPrompt\n\nCONTEXT\n$contextBlock"
        }
}

/**
 * The outcome of asking the assembler for a coach request. Exactly one of:
 * a prompt is ready, the profile is red-flag blocked (refusal — no plan
 * content exists), or the feature needs a cloud opt-in the user hasn't made.
 */
sealed interface CoachRequestPlan {

    /** A prompt is assembled and cleared to run in [AssembledCoachPrompt.mode]. */
    data class Ready(val prompt: AssembledCoachPrompt) : CoachRequestPlan

    /**
     * Red-flag refusal (blueprint 3.0 red-flag rule, Evidence Base P9): the
     * gate has blocked plan generation pending medical clearance, so NO
     * prompt is assembled and NO plan content is produced — [message] is
     * shown to the user instead. [redFlagsPresent] rides along for the app's
     * own clearance UI; it is never rendered into a prompt.
     */
    data class Refused(
        val message: String,
        val redFlagsPresent: List<String>,
    ) : CoachRequestPlan

    /**
     * The feature requires cloud quality (blueprint 3.8/3.9) but cloud is
     * not configured: the app must prompt the user to enable it rather than
     * send anything. No prompt payload exists in this state.
     */
    data class NeedsCloudOptIn(
        val feature: CloudQualityFeature,
        val message: String,
    ) : CoachRequestPlan
}

/**
 * Coach prompt assembly (blueprint 3.8 persona + guardrails, 3.0 propagation
 * rule 3, 3.15 grounding rule, Evidence Base P2 reply discipline and P9
 * red-flag triage).
 *
 * What may enter a prompt, exhaustively:
 * - the coach's own name and tone (the persona is the product, not the user);
 * - the ground rules written below;
 * - [FunctionalConstraints.promptConstraints] **verbatim** — the gate's
 *   functional, non-diagnostic statements, which are the only health-derived
 *   content the blueprint allows across this boundary;
 * - a rendered [CoachContext] (derived aggregates only, by construction).
 *
 * The user's name, diagnoses, and raw health records are not parameters of
 * anything in this file, so they cannot leak into a prompt.
 */
object CoachPromptAssembler {

    /** Shown instead of any plan when the gate blocks generation (blueprint 3.0). */
    const val REFUSAL_MESSAGE: String =
        "Coaching and plan generation are paused. Your onboarding health screen requires " +
            "medical clearance for exercise before Forge can build or adjust training or " +
            "diet plans. Once your clearance is recorded in your profile, the coach " +
            "unlocks — and nothing from your health screen is sent anywhere in the meantime."

    /**
     * Plan one coach request.
     *
     * Decision order is deliberate:
     * 1. **Blocked beats everything.** A red-flag profile gets the refusal,
     *    even for a feature that would also need cloud — safety state first.
     * 2. **Cloud gating.** A cloud-quality feature without configured cloud
     *    surfaces the opt-in state instead of a payload (blueprint 3.8:
     *    features prompt, they never send silently).
     * 3. Otherwise the prompt is assembled for [AiSettings.effectiveMode].
     */
    fun plan(
        coach: CoachProfile,
        gate: OnboardingGateResult,
        context: CoachContext? = null,
        unit: WeightUnit = WeightUnit.LB,
        aiSettings: AiSettings = AiSettings(),
        feature: CloudQualityFeature = CloudQualityFeature.MORNING_BRIEF,
    ): CoachRequestPlan {
        if (gate.planGenerationBlocked) {
            return CoachRequestPlan.Refused(
                message = REFUSAL_MESSAGE,
                redFlagsPresent = gate.redFlagsPresent,
            )
        }
        if (aiSettings.needsCloudPromptFor(feature)) {
            return CoachRequestPlan.NeedsCloudOptIn(
                feature = feature,
                message = "This needs cloud-quality AI (${featureLabel(feature)}). Cloud is an " +
                    "opt-in in Forge: add your own API key in Settings → AI Coach to enable " +
                    "it. Nothing is sent anywhere until you do.",
            )
        }
        return CoachRequestPlan.Ready(
            AssembledCoachPrompt(
                coachName = coach.name,
                tone = coach.tone,
                mode = aiSettings.effectiveMode(),
                systemPrompt = systemPrompt(coach, gate.constraints),
                contextBlock = context?.render(unit),
            ),
        )
    }

    /**
     * The system prompt: persona, tone instruction, ground rules, and the
     * gate's functional constraints verbatim. Deterministic — the same
     * inputs always produce the same text, in either AI mode.
     */
    fun systemPrompt(coach: CoachProfile, constraints: FunctionalConstraints): String = buildString {
        appendLine(
            "You are ${coach.name}, the user's personal strength and nutrition coach in the " +
                "Forge app. Speak in first person as ${coach.name}.",
        )
        appendLine()
        appendLine("TONE")
        appendLine(toneInstruction(coach.tone))
        appendLine()
        appendLine("GROUND RULES (non-negotiable)")
        appendLine(
            "- Use only the numbers in the CONTEXT block and the targets stated there. " +
                "Never invent a target, threshold, weight, or adjustment (blueprint 3.15 " +
                "grounding rule). Anything you add that is not in the context must be " +
                "labelled as your opinion.",
        )
        appendLine(
            "- You coach training and food only. This is not medical advice: you do not " +
                "diagnose, interpret medical tests, or assess symptoms.",
        )
        appendLine(
            "- If the user reports pain, the answer is to back off that movement — never " +
                "push through pain. If they describe chest pain, fainting, a fresh injury, " +
                "or signs of an eating disorder, stop training and diet advice and direct " +
                "them to qualified care.",
        )
        appendLine(
            "- Reply format: the first line states the key fact from the context, " +
                "plainly. Then give exactly one lever — the single most valuable change " +
                "(training, diet, or recovery) — and stop. Short beats thorough.",
        )
        appendLine()
        appendLine("HARD CONSTRAINTS FROM ONBOARDING (plan around these, never through them)")
        if (constraints.promptConstraints.isEmpty()) {
            appendLine("- None declared.")
        } else {
            constraints.promptConstraints.forEach { appendLine("- $it") }
        }
    }.trimEnd()

    /** One instruction line per tone (blueprint 3.8: same brain, different voice). */
    private fun toneInstruction(tone: CoachTone): String = when (tone) {
        CoachTone.SUPPORTIVE ->
            "Supportive: warm and encouraging, matter-of-fact about missed work, never a " +
                "guilt trip. Credit effort and consistency before correcting."
        CoachTone.DRILL_SERGEANT ->
            "Drill-sergeant: terse, direct, demanding. Call out skipped work plainly and " +
                "expect the standard to be met. The tone never extends to the user's body " +
                "or health, and the ground rules above outrank it."
        CoachTone.STRAIGHT_DATA ->
            "Straight-data: numbers and facts only. No encouragement, no praise, no " +
                "commentary beyond the measurement, the comparison, and the one lever."
    }

    private fun featureLabel(feature: CloudQualityFeature): String = when (feature) {
        CloudQualityFeature.FULL_PROGRAM_GENERATION -> "full program generation"
        CloudQualityFeature.PHOTO_MEAL_ANALYSIS -> "photo meal analysis"
        CloudQualityFeature.MORNING_BRIEF -> "morning brief"
        CloudQualityFeature.SESSION_DEBRIEF -> "session debrief"
    }
}
