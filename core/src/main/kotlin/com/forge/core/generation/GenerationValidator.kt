package com.forge.core.generation

import com.forge.core.data.ExerciseLibrary
import com.forge.core.model.Exercise
import com.forge.core.model.GoalProfile
import com.forge.core.nutrition.MacroTargets
import com.forge.core.onboarding.ExerciseExclusionMatcher
import com.forge.core.onboarding.FunctionalConstraints
import com.forge.core.onboarding.OnboardingGateResult
import kotlin.math.abs

/** Whether a generated workout instruction holds intensity steady, lowers it, or raises it. */
enum class IntensityDirective {
    DECREASE,
    MAINTAIN,
    INCREASE,
}

/**
 * One exercise in an AI-generated workout (blueprint 3.9). The exercise is
 * referenced the way a generator naturally emits it: by library [exerciseId]
 * when it has one, otherwise by [name]. [heartRateCeilingPercentOfMax] and
 * [intensityDirective] are the structured intensity intent the validator
 * checks against the onboarding HR rules — free-text intensity claims are
 * not trusted, so they are not part of this shape.
 */
data class GeneratedExercise(
    val name: String,
    val exerciseId: String? = null,
    val sets: Int,
    val repRange: IntRange,
    val targetWeightKg: Double? = null,
    val heartRateCeilingPercentOfMax: Int? = null,
    val intensityDirective: IntensityDirective = IntensityDirective.MAINTAIN,
)

/** One routine/session inside a generated workout plan, with its own optional intensity intent. */
data class GeneratedWorkoutRoutine(
    val name: String,
    val exercises: List<GeneratedExercise>,
    val heartRateCeilingPercentOfMax: Int? = null,
    val intensityDirective: IntensityDirective = IntensityDirective.MAINTAIN,
)

/** A generated workout program: one or more routines plus plan-level intensity intent. */
data class GeneratedWorkoutPlan(
    val name: String,
    val routines: List<GeneratedWorkoutRoutine>,
    val heartRateCeilingPercentOfMax: Int? = null,
    val intensityDirective: IntensityDirective = IntensityDirective.MAINTAIN,
)

/**
 * One food item in a generated meal plan. [ingredients] is the item's
 * ingredient list as generated: null (or empty) means the generator did not
 * disclose ingredients, which the validator treats as unverifiable rather
 * than safe. [caloriesKcal] is the item's stated calories, when known.
 */
data class GeneratedMealItem(
    val name: String,
    val ingredients: List<String>? = null,
    val caloriesKcal: Int? = null,
)

/** One meal in a generated meal plan. */
data class GeneratedMeal(
    val name: String,
    val items: List<GeneratedMealItem>,
)

/**
 * One day of a generated meal plan. [declaredCaloriesKcal] is the day's
 * stated total when the generator provides one; otherwise the validator
 * sums item calories, and can only do so when every item states them.
 */
data class GeneratedMealDay(
    val label: String,
    val meals: List<GeneratedMeal>,
    val declaredCaloriesKcal: Int? = null,
)

/** A generated meal plan: days of meals of items. */
data class GeneratedMealPlan(
    val name: String,
    val days: List<GeneratedMealDay>,
)

enum class ValidationSeverity {
    /** A hard failure: the plan must not be presented as-is (blueprint 3.9). */
    VIOLATION,

    /** Not a hard failure, but must be surfaced — never silently swallowed. */
    WARNING,
}

/** The rule a [ValidationFinding] came from. */
enum class ValidationRule {
    UNKNOWN_EXERCISE,
    EXCLUDED_EXERCISE,
    HEART_RATE_CAP_EXCEEDED,
    SCALE_DOWN_ONLY_VIOLATION,
    ALLERGEN_PRESENT,
    DIETARY_PATTERN_VIOLATION,
    DISLIKED_FOOD,
    UNVERIFIABLE_INGREDIENTS,
    CALORIES_OUT_OF_RANGE,
    UNVERIFIABLE_CALORIES,

    /** A day's declared total contradicts the calories its own items add up to. */
    CALORIE_TOTAL_MISMATCH,
}

/**
 * One structured validation finding. [subject] is the offending exercise,
 * food item, routine, or day in plain language; [location] says where it
 * sits in the plan; [reason] is the plain-language explanation a user can
 * be shown; [correction] is the concrete fix a regeneration should apply.
 */
data class ValidationFinding(
    val severity: ValidationSeverity,
    val rule: ValidationRule,
    val subject: String,
    val location: String,
    val reason: String,
    val correction: String? = null,
)

/**
 * The verdict on a generated plan (blueprint 3.9): a plan is either fit to
 * present ([Valid], possibly carrying warnings that must be surfaced),
 * unfit ([Invalid], with the structured violations a regeneration must
 * fix), or not validated at all because generation itself is refused
 * ([Refused] — a red-flag profile gets no generated plan, validated or not).
 */
sealed interface GenerationValidationResult {

    data class Valid(
        val warnings: List<ValidationFinding> = emptyList(),
    ) : GenerationValidationResult {
        init {
            require(warnings.all { it.severity == ValidationSeverity.WARNING }) {
                "Valid results may only carry warnings"
            }
        }
    }

    data class Invalid(
        val violations: List<ValidationFinding>,
        val warnings: List<ValidationFinding> = emptyList(),
    ) : GenerationValidationResult {
        init {
            require(violations.isNotEmpty()) { "Invalid results must carry at least one violation" }
            require(violations.all { it.severity == ValidationSeverity.VIOLATION }) {
                "Violations must all carry VIOLATION severity"
            }
            require(warnings.all { it.severity == ValidationSeverity.WARNING }) {
                "Warnings must all carry WARNING severity"
            }
        }
    }

    data class Refused(
        val reason: String,
        val redFlagsPresent: List<String> = emptyList(),
    ) : GenerationValidationResult
}

/**
 * Blueprint 3.9's safety validation pass: deterministic checks run on every
 * AI-generated workout and meal plan after generation and before the plan
 * is shown. A plan that fails is regenerated or flagged — never presented
 * as-is. The validator trusts nothing the generator said about its own
 * compliance: exercises are re-vetted against the same
 * [ExerciseExclusionMatcher] the live session engine uses, and calories are
 * compared against the formula-set [MacroTargets], never the model's own
 * target.
 */
object GenerationValidator {

    /**
     * Allowed deviation between a generated day's calories and the
     * formula-set target: ±10%. Chosen because a composed plan (recipes with
     * stated calories, not photo estimates) should land close to the target
     * it was generated against; outside ±10% is roughly ±200 kcal on a
     * 2,000 kcal day — a materially different day of eating, not rounding.
     * This is a validator default, not a blueprint number.
     */
    const val CALORIE_TOLERANCE_FRACTION: Double = 0.10

    /** Declared total vs item sum: room for per-item rounding, none for a different day. */
    const val CALORIE_TOTAL_MISMATCH_FRACTION: Double = 0.05

    // -- Workouts ---------------------------------------------------------

    /**
     * Validates a generated workout against the gate's verdict and the
     * functional constraints it produced. A red-flag-blocked (or otherwise
     * not-yet-generatable) gate refuses the whole plan outright. Otherwise
     * every exercise must resolve against [library] and pass the shared
     * exclusion matcher, and every structured intensity intent must respect
     * the HR cap and the scale-down-only rule (blueprint 3.0).
     */
    fun validateWorkout(
        plan: GeneratedWorkoutPlan,
        gate: OnboardingGateResult,
        library: List<Exercise> = ExerciseLibrary.all,
    ): GenerationValidationResult {
        refused(gate)?.let { return it }
        val constraints = gate.constraints
        val findings = mutableListOf<ValidationFinding>()

        checkIntensity(
            subject = plan.name,
            location = "workout plan",
            heartRateCeilingPercentOfMax = plan.heartRateCeilingPercentOfMax,
            intensityDirective = plan.intensityDirective,
            constraints = constraints,
            findings = findings,
        )
        for (routine in plan.routines) {
            checkIntensity(
                subject = routine.name,
                location = "routine in '${plan.name}'",
                heartRateCeilingPercentOfMax = routine.heartRateCeilingPercentOfMax,
                intensityDirective = routine.intensityDirective,
                constraints = constraints,
                findings = findings,
            )
            for (generated in routine.exercises) {
                val location = "'${routine.name}' in '${plan.name}'"
                val resolved = resolveExercise(generated, library)
                if (resolved == null) {
                    findings += violation(
                        rule = ValidationRule.UNKNOWN_EXERCISE,
                        subject = generated.name,
                        location = location,
                        reason = "'${generated.name}' could not be resolved against the exercise " +
                            "library, so its safety cannot be vetted.",
                        correction = "Replace '${generated.name}' with an exercise from the " +
                            "Forge exercise library, referenced by its library name or id.",
                    )
                } else {
                    val match = ExerciseExclusionMatcher.match(resolved, constraints)
                    if (match != null) {
                        val safeSubstitutes = match.exclusion.substitutions
                            .mapNotNull { candidate -> resolveLibraryExerciseByName(candidate, library) }
                            .filter { substitute -> ExerciseExclusionMatcher.match(substitute, constraints) == null }
                            .map { it.name }
                            .distinct()
                        findings += violation(
                            rule = ValidationRule.EXCLUDED_EXERCISE,
                            subject = generated.name,
                            location = location,
                            reason = "'${resolved.name}' is excluded: ${match.matchedDescription}. " +
                                match.exclusion.reason,
                            correction = if (safeSubstitutes.isEmpty()) {
                                "Remove '${resolved.name}'; no vetted substitute is available " +
                                    "in the library under the active limitations."
                            } else {
                                "Replace '${resolved.name}' with one of: " +
                                    safeSubstitutes.joinToString(", ") + "."
                            },
                        )
                    }
                }
                checkIntensity(
                    subject = generated.name,
                    location = location,
                    heartRateCeilingPercentOfMax = generated.heartRateCeilingPercentOfMax,
                    intensityDirective = generated.intensityDirective,
                    constraints = constraints,
                    findings = findings,
                )
            }
        }
        return resultOf(findings)
    }

    private fun checkIntensity(
        subject: String,
        location: String,
        heartRateCeilingPercentOfMax: Int?,
        intensityDirective: IntensityDirective,
        constraints: FunctionalConstraints,
        findings: MutableList<ValidationFinding>,
    ) {
        val cap = constraints.heartRateCapPercentOfMax
        if (cap != null && heartRateCeilingPercentOfMax != null && heartRateCeilingPercentOfMax > cap) {
            findings += violation(
                rule = ValidationRule.HEART_RATE_CAP_EXCEEDED,
                subject = subject,
                location = location,
                reason = "'$subject' allows working heart rate up to " +
                    "$heartRateCeilingPercentOfMax% of max, above the onboarding cap of $cap%.",
                correction = "Lower the heart-rate ceiling for '$subject' to at most $cap% of max.",
            )
        }
        if (constraints.intensityMayOnlyScaleDown && intensityDirective == IntensityDirective.INCREASE) {
            findings += violation(
                rule = ValidationRule.SCALE_DOWN_ONLY_VIOLATION,
                subject = subject,
                location = location,
                reason = "'$subject' instructs an intensity increase, but this profile may " +
                    "only scale intensity down, never up (blueprint 3.0).",
                correction = "Change the intensity instruction for '$subject' to maintain " +
                    "or decrease — never increase.",
            )
        }
    }

    /**
     * Resolves a generated exercise reference. A supplied library id is
     * authoritative; without one, the name must match a library exercise's
     * name exactly after normalization. Anything else is unknown — the
     * validator never waves through an exercise it cannot vet.
     */
    private fun resolveExercise(generated: GeneratedExercise, library: List<Exercise>): Exercise? {
        val id = generated.exerciseId
        if (!id.isNullOrBlank()) return library.firstOrNull { it.id == id }
        return resolveLibraryExerciseByName(generated.name, library, exactOnly = true)
    }

    /**
     * Resolves a plain-language exercise name (e.g. a substitution phrase
     * from the gate's constraint data) to a library exercise: exact
     * normalized name first, then the longest multi-word library name that
     * appears inside a descriptive phrase ("Box squat to comfortable depth"
     * → Box Squat). Mirrors the session engine's substitution resolution.
     */
    private fun resolveLibraryExerciseByName(
        name: String,
        library: List<Exercise>,
        exactOnly: Boolean = false,
    ): Exercise? {
        library.firstOrNull { normalizeName(it.name) == normalizeName(name) }?.let { return it }
        if (exactOnly) return null
        val candidateTokens = wordTokens(name)
        return library.filter { exercise ->
            val tokens = wordTokens(exercise.name)
            tokens.size >= 2 && candidateTokens.containsAll(tokens)
        }.maxByOrNull { wordTokens(it.name).size }
    }

    // -- Meal plans ---------------------------------------------------------

    /**
     * Validates a generated meal plan against the user's diet data and the
     * formula-set [targets] (the same targets apply to every day of the
     * plan; training/rest-day selection happens before this call, exactly
     * as the diet views do it). When a [gate] result is supplied, a
     * red-flag-blocked profile refuses the plan outright, as for workouts.
     *
     * Allergen, pattern, and dislike matching — the honest rules:
     * - Matching runs over the item's name **and** every disclosed
     *   ingredient, case-insensitively, with punctuation/hyphens treated as
     *   spaces.
     * - A term matches only as a whole word or whole multi-word phrase
     *   (token sequence), never as a substring: "egg" does not match
     *   "eggplant". Simple English plurals are normalized on both sides
     *   (trailing -s/-es, -ies → -y), so "peanut" matches "Peanuts" and
     *   "peanut butter".
     * - Limits: hidden derivatives and synonyms are NOT detected (casein
     *   does not trip a "milk" allergy; albumin does not trip "egg")
     *   unless they are listed as allergies themselves, and an ambiguous
     *   whole word (e.g. "milk" inside "oat milk") is flagged rather than
     *   reasoned about — safety first.
     * - An item whose ingredient list is missing or empty **cannot be
     *   verified**: it earns an UNVERIFIABLE_INGREDIENTS warning (its name
     *   is still checked), never a silent pass.
     * - Dietary patterns are enforced only for the recognized patterns in
     *   [DietaryPatternRules] (vegetarian, vegan, pescatarian) via the
     *   documented term lists there. Certification-based patterns (halal,
     *   kosher) cannot be verified from ingredient names and are not
     *   inferred; free-text patterns the validator does not recognize are
     *   not enforced.
     */
    fun validateMealPlan(
        plan: GeneratedMealPlan,
        profile: GoalProfile,
        targets: MacroTargets,
        gate: OnboardingGateResult? = null,
    ): GenerationValidationResult {
        if (gate != null) refused(gate)?.let { return it }
        val findings = mutableListOf<ValidationFinding>()
        val patternRules = DietaryPatternRules.forPattern(profile.dietaryPattern)

        for (day in plan.days) {
            for (meal in day.meals) {
                val location = "${day.label} / ${meal.name} in '${plan.name}'"
                for (item in meal.items) {
                    checkMealItem(item, location, profile, patternRules, findings)
                }
            }
            checkDayCalories(day, plan.name, targets, findings)
        }
        return resultOf(findings)
    }

    private fun checkMealItem(
        item: GeneratedMealItem,
        location: String,
        profile: GoalProfile,
        patternRules: List<DietaryPatternRule>,
        findings: MutableList<ValidationFinding>,
    ) {
        // The texts an item can be judged on: its name plus each disclosed ingredient.
        val parts = buildList {
            add(item.name)
            item.ingredients?.forEach { add(it) }
        }

        for (allergen in profile.allergies) {
            if (allergen.isBlank()) continue
            val matchedPart = parts.firstOrNull { containsPhrase(it, allergen) } ?: continue
            findings += violation(
                rule = ValidationRule.ALLERGEN_PRESENT,
                subject = item.name,
                location = location,
                reason = "'${item.name}' contains the declared allergen '$allergen' " +
                    "(matched in '$matchedPart'). Allergies are hard constraints.",
                correction = "Remove '${item.name}' or replace it with a version containing " +
                    "no $allergen.",
            )
        }

        for (rule in patternRules) {
            val matchedTerm = rule.prohibitedTerms.firstOrNull { term ->
                parts.any { containsPhrase(it, term) }
            } ?: continue
            val matchedPart = parts.first { containsPhrase(it, matchedTerm) }
            findings += violation(
                rule = ValidationRule.DIETARY_PATTERN_VIOLATION,
                subject = item.name,
                location = location,
                reason = "'${item.name}' contains '$matchedTerm' (in '$matchedPart'), which " +
                    "violates the ${rule.patternName} dietary pattern.",
                correction = "Replace '${item.name}' with a ${rule.patternName} alternative " +
                    "containing no ${rule.prohibitedDescription}.",
            )
        }

        for (dislike in profile.dislikes) {
            if (dislike.isBlank()) continue
            val matchedPart = parts.firstOrNull { containsPhrase(it, dislike) } ?: continue
            findings += ValidationFinding(
                severity = ValidationSeverity.WARNING,
                rule = ValidationRule.DISLIKED_FOOD,
                subject = item.name,
                location = location,
                reason = "'${item.name}' contains '$dislike' (matched in '$matchedPart'), " +
                    "which the user dislikes. A preference, not a safety issue.",
                correction = "Swap '${item.name}' for something without $dislike if a " +
                    "suitable alternative exists.",
            )
        }

        if (item.ingredients.isNullOrEmpty()) {
            findings += ValidationFinding(
                severity = ValidationSeverity.WARNING,
                rule = ValidationRule.UNVERIFIABLE_INGREDIENTS,
                subject = item.name,
                location = location,
                reason = "'${item.name}' has no disclosed ingredient list, so its allergen " +
                    "and dietary-pattern compliance cannot be verified — only its name " +
                    "was checked.",
                correction = "Obtain the full ingredient list for '${item.name}' and " +
                    "re-validate before serving it.",
            )
        }
    }

    private fun checkDayCalories(
        day: GeneratedMealDay,
        planName: String,
        targets: MacroTargets,
        findings: MutableList<ValidationFinding>,
    ) {
        val allItems = day.meals.flatMap { it.items }
        val itemTotal = if (allItems.isNotEmpty() && allItems.all { it.caloriesKcal != null }) {
            allItems.sumOf { it.caloriesKcal ?: 0 }
        } else {
            null
        }
        val declared = day.declaredCaloriesKcal
        val location = "${day.label} in '$planName'"
        // A declared total can't vouch for itself against the items it describes:
        // when they disagree, the plan is inconsistent and the items (what would
        // actually be eaten) are what's judged against the target.
        if (declared != null && itemTotal != null &&
            abs(declared - itemTotal) > CALORIE_TOTAL_MISMATCH_FRACTION * maxOf(declared, itemTotal)
        ) {
            findings += violation(
                rule = ValidationRule.CALORIE_TOTAL_MISMATCH,
                subject = day.label,
                location = location,
                reason = "${day.label} declares $declared kcal, but its items add up to " +
                    "$itemTotal kcal. The day can't be trusted until the two agree.",
                correction = "Make ${day.label}'s stated total match its items, or fix the " +
                    "item calories, and re-validate.",
            )
        }
        val actual = itemTotal ?: declared
        if (actual == null) {
            findings += ValidationFinding(
                severity = ValidationSeverity.WARNING,
                rule = ValidationRule.UNVERIFIABLE_CALORIES,
                subject = day.label,
                location = location,
                reason = "${day.label} states no day total and not every item states its " +
                    "calories, so the day cannot be checked against the " +
                    "${targets.caloriesKcal} kcal target.",
                correction = "State calories for every item on ${day.label}, or state " +
                    "the day's total, and re-validate.",
            )
            return
        }
        val lower = targets.caloriesKcal * (1.0 - CALORIE_TOLERANCE_FRACTION)
        val upper = targets.caloriesKcal * (1.0 + CALORIE_TOLERANCE_FRACTION)
        if (actual < lower - EPSILON || actual > upper + EPSILON) {
            findings += violation(
                rule = ValidationRule.CALORIES_OUT_OF_RANGE,
                subject = day.label,
                location = location,
                reason = "${day.label} totals $actual kcal against a formula-set target of " +
                    "${targets.caloriesKcal} kcal — outside the ±" +
                    "${(CALORIE_TOLERANCE_FRACTION * 100).toInt()}% band " +
                    "(${lower.toInt()}–${upper.toInt()} kcal). Calorie targets come from " +
                    "formulas, not the generating model.",
                correction = "Rebalance ${day.label} to land within " +
                    "${lower.toInt()}–${upper.toInt()} kcal.",
            )
        }
    }

    // -- Regeneration guidance ----------------------------------------------

    /**
     * The compact correction note the app appends to a regeneration prompt
     * (blueprint 3.9: a failing plan is regenerated or flagged, never
     * presented as-is; slice 8's prompt discipline — facts first, concrete
     * levers). For a refused result the note is a stop instruction, not a
     * regeneration request.
     */
    fun regenerationNote(result: GenerationValidationResult): String = when (result) {
        is GenerationValidationResult.Refused ->
            "Do not regenerate or present any plan yet. ${result.reason}"
        is GenerationValidationResult.Valid ->
            if (result.warnings.isEmpty()) {
                "No corrections needed: the plan passed Forge's safety validation."
            } else {
                "The plan passed safety validation with warnings. Surface these to the " +
                    "user and address them if you regenerate: " +
                    result.warnings.joinToString("; ") { finding ->
                        "${finding.subject} — ${finding.reason}" +
                            (finding.correction?.let { " Fix: $it" } ?: "")
                    }
            }
        is GenerationValidationResult.Invalid ->
            "Regenerate the plan with these corrections: " +
                result.violations.joinToString("; ") { finding ->
                    "${finding.subject} — ${finding.reason}" +
                        (finding.correction?.let { " Fix: $it" } ?: "")
                } +
                if (result.warnings.isEmpty()) {
                    ". Do not reintroduce any corrected item."
                } else {
                    ". Also resolve these warnings: " +
                        result.warnings.joinToString("; ") { it.subject } +
                        ". Do not reintroduce any corrected item."
                }
    }

    // -- Shared helpers -------------------------------------------------------

    private fun refused(gate: OnboardingGateResult): GenerationValidationResult.Refused? = when {
        gate.planGenerationBlocked -> GenerationValidationResult.Refused(
            reason = "Generation is refused: the onboarding health screen is blocked " +
                "pending medical clearance, so no generated plan may be produced or " +
                "shown (blueprint 3.0 red-flag rule).",
            redFlagsPresent = gate.redFlagsPresent,
        )
        !gate.canGeneratePlans -> GenerationValidationResult.Refused(
            reason = "Generation is refused: onboarding is not complete, and Forge " +
                "generates nothing before the onboarding gate passes (blueprint 3.0).",
            redFlagsPresent = gate.redFlagsPresent,
        )
        else -> null
    }

    private fun resultOf(findings: List<ValidationFinding>): GenerationValidationResult {
        val violations = findings.filter { it.severity == ValidationSeverity.VIOLATION }
        val warnings = findings.filter { it.severity == ValidationSeverity.WARNING }
        return if (violations.isEmpty()) {
            GenerationValidationResult.Valid(warnings)
        } else {
            GenerationValidationResult.Invalid(violations, warnings)
        }
    }

    private fun violation(
        rule: ValidationRule,
        subject: String,
        location: String,
        reason: String,
        correction: String,
    ): ValidationFinding = ValidationFinding(
        severity = ValidationSeverity.VIOLATION,
        rule = rule,
        subject = subject,
        location = location,
        reason = reason,
        correction = correction,
    )

    private const val EPSILON = 1e-9
}

/**
 * The recognized dietary-pattern rules (blueprint 3.7/3.8). A deliberately
 * small, documented set: the pattern string on the profile is free text
 * (e.g. "vegetarian"), so the validator recognizes these three patterns by
 * keyword and enforces them with the term lists below. Terms are whole
 * words/phrases under the validator's matching rules (see
 * [GenerationValidator.validateMealPlan]).
 */
data class DietaryPatternRule(
    val patternName: String,
    val prohibitedTerms: List<String>,
    /** Plain-language summary of what is prohibited, for reasons and corrections. */
    val prohibitedDescription: String,
)

object DietaryPatternRules {

    private val meatAndPoultry = listOf(
        "beef", "steak", "pork", "ham", "bacon", "sausage", "pepperoni", "salami",
        "prosciutto", "chorizo", "chicken", "turkey", "duck", "lamb", "veal",
        "venison", "goat", "meat", "mince", "gelatin", "gelatine", "lard",
    )

    private val seafood = listOf(
        "fish", "salmon", "tuna", "cod", "haddock", "trout", "sea bass", "shrimp",
        "prawn", "crab", "lobster", "oyster", "clam", "mussel", "scallop",
        "anchovy", "sardine", "seafood",
    )

    /** Animal-derived ingredients a vegan pattern excludes beyond meat and seafood. */
    private val otherAnimalProducts = listOf(
        "milk", "cheese", "butter", "cream", "yogurt", "yoghurt", "egg", "honey",
        "whey", "casein", "mayonnaise", "custard",
    )

    val vegetarian = DietaryPatternRule(
        patternName = "vegetarian",
        prohibitedTerms = meatAndPoultry + seafood,
        prohibitedDescription = "meat, poultry, seafood, or gelatin",
    )

    val vegan = DietaryPatternRule(
        patternName = "vegan",
        prohibitedTerms = meatAndPoultry + seafood + otherAnimalProducts,
        prohibitedDescription = "meat, poultry, seafood, dairy, egg, honey, or other animal products",
    )

    val pescatarian = DietaryPatternRule(
        patternName = "pescatarian",
        prohibitedTerms = meatAndPoultry,
        prohibitedDescription = "meat or poultry",
    )

    /**
     * The rules a free-text dietary pattern activates. "Vegan" wins over
     * "vegetarian" when both appear (the stricter rule subsumes it);
     * unrecognized patterns activate nothing — the validator does not guess
     * at rules it does not have.
     */
    fun forPattern(dietaryPattern: String?): List<DietaryPatternRule> {
        if (dietaryPattern.isNullOrBlank()) return emptyList()
        val normalized = dietaryPattern.lowercase()
        return buildList {
            when {
                "vegan" in normalized -> add(vegan)
                "vegetarian" in normalized -> add(vegetarian)
            }
            if ("pescatarian" in normalized || "pescetarian" in normalized) add(pescatarian)
        }
    }
}

// -- Text matching (meal-plan checks) --------------------------------------

/**
 * Whole-word/phrase containment under the validator's documented matching
 * rules: both sides are lowercased, punctuation becomes spaces, and each
 * token is plural-normalized; [phrase] then matches when its token
 * sequence appears contiguously in [text]'s tokens.
 */
private fun containsPhrase(text: String, phrase: String): Boolean {
    val haystack = canonicalTokens(text)
    val needle = canonicalTokens(phrase)
    if (needle.isEmpty() || needle.size > haystack.size) return false
    for (start in 0..haystack.size - needle.size) {
        if (haystack.subList(start, start + needle.size) == needle) return true
    }
    return false
}

private fun canonicalTokens(text: String): List<String> =
    text.lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter { it.isNotEmpty() }
        .map(::canonicalToken)

/**
 * Simple English plural normalization, applied identically to terms and to
 * candidate text so both sides meet in singular form: -ies → -y
 * ("berries" → "berry"), -es after a sibilant or -o ("peaches" → "peach",
 * "tomatoes" → "tomato"), and a plain trailing -s ("peanuts" → "peanut").
 * Words ending in -ss or -us are left alone ("glass", "hummus").
 */
private fun canonicalToken(token: String): String = when {
    token.length > 3 && token.endsWith("ies") ->
        token.dropLast(3) + "y"
    token.length > 4 && (
        token.endsWith("ches") || token.endsWith("shes") || token.endsWith("sses") ||
            token.endsWith("xes") || token.endsWith("zes") || token.endsWith("oes")
        ) ->
        token.dropLast(2)
    token.length > 3 && token.endsWith("s") && !token.endsWith("ss") && !token.endsWith("us") ->
        token.dropLast(1)
    else -> token
}

private fun wordTokens(name: String): Set<String> =
    name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()

private fun normalizeName(name: String): String = wordTokens(name).joinToString(" ")
