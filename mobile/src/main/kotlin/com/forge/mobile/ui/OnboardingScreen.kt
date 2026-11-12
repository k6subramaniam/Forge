package com.forge.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.forge.core.model.ActivityLevel
import com.forge.core.model.ClearanceState
import com.forge.core.model.CoachProfile
import com.forge.core.model.CoachTone
import com.forge.core.model.EquipmentAccess
import com.forge.core.model.ExperienceLevel
import com.forge.core.model.GoalProfile
import com.forge.core.model.GoalType
import com.forge.core.model.HealthProfile
import com.forge.core.model.JointArea
import com.forge.core.model.Sex
import com.forge.core.model.UserPreferences
import com.forge.core.onboarding.OnboardingGate
import com.forge.core.onboarding.OnboardingGateResult
import com.forge.mobile.data.ForgeRepository

/**
 * Blueprint 3.0 as a flow: goal → body stats → health screen → diet screen → coach
 * setup, with the red-flag clearance screen inserted when the gate in :core blocks
 * plan generation. Nothing else in the app is reachable until this completes —
 * there is no skip.
 */
private enum class OnboardingStep { GOAL, BODY, HEALTH, DIET, COACH, CLEARANCE }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    repository: ForgeRepository,
    onFinished: () -> Unit,
) {
    var step by remember { mutableStateOf(OnboardingStep.GOAL) }

    // Step 1 — goal
    var goal by remember { mutableStateOf(GoalType.BUILD_MUSCLE) }
    var trainingDays by remember { mutableIntStateOf(3) }
    var experience by remember { mutableStateOf(ExperienceLevel.INTERMEDIATE) }
    var equipment by remember { mutableStateOf(EquipmentAccess.FULL_GYM) }

    // Step 2 — body stats
    var ageText by remember { mutableStateOf("") }
    var heightText by remember { mutableStateOf("") }
    var weightText by remember { mutableStateOf("") }
    var sex by remember { mutableStateOf(Sex.MALE) }
    var activity by remember { mutableStateOf(ActivityLevel.MODERATE) }

    // Step 3 — health screen (PAR-Q style)
    var heartCondition by remember { mutableStateOf(false) }
    var highBloodPressure by remember { mutableStateOf(false) }
    var chestPain by remember { mutableStateOf(false) }
    var doctorAdvisedAgainst by remember { mutableStateOf(false) }
    var asthma by remember { mutableStateOf(false) }
    var diabetes by remember { mutableStateOf(false) }
    var hrMedication by remember { mutableStateOf(false) }
    var jointLimitations by remember { mutableStateOf<Set<JointArea>>(emptySet()) }
    var injuriesText by remember { mutableStateOf("") }
    var freeTextLimitations by remember { mutableStateOf("") }

    // Step 4 — diet screen
    var dietaryPatternText by remember { mutableStateOf("") }
    var allergiesText by remember { mutableStateOf("") }
    var dislikesText by remember { mutableStateOf("") }
    var cuisinesText by remember { mutableStateOf("") }

    // Step 5 — coach setup (blueprint 3.0 step 6)
    var coachNameText by remember { mutableStateOf("") }
    var coachTone by remember { mutableStateOf(CoachTone.SUPPORTIVE) }

    var gateResult by remember { mutableStateOf<OnboardingGateResult?>(null) }

    fun buildGoalProfile() = GoalProfile(
        goal = goal,
        ageYears = ageText.toIntOrNull() ?: 0,
        sex = sex,
        heightCm = heightText.toDoubleOrNull() ?: 0.0,
        weightKg = weightText.toDoubleOrNull() ?: 0.0,
        trainingDaysPerWeek = trainingDays,
        experienceLevel = experience,
        equipmentAccess = equipment,
        activityLevel = activity,
        dietaryPattern = dietaryPatternText.trim().ifBlank { null },
        dislikes = splitList(dislikesText),
        allergies = splitList(allergiesText),
        likedCuisines = splitList(cuisinesText),
        // Reaching the end of the flow means this screen was answered — an
        // all-"none" answer still counts as completed (blueprint 3.0 step 5).
        dietScreenCompleted = true,
    )

    fun buildPreferences() = UserPreferences(
        coach = CoachProfile(
            name = coachNameText.trim(),
            tone = coachTone,
        ),
    )

    fun buildHealthProfile(clearance: ClearanceState) = HealthProfile(
        hasDiagnosedHeartCondition = heartCondition,
        hasHighBloodPressure = highBloodPressure,
        hasExertionalChestPain = chestPain,
        doctorAdvisedAgainstExercise = doctorAdvisedAgainst,
        hasAsthma = asthma,
        hasDiabetes = diabetes,
        takesHeartRateAffectingMedication = hrMedication,
        jointLimitations = jointLimitations,
        injuriesOrSurgeries = injuriesText.trim(),
        freeTextLimitations = freeTextLimitations.trim(),
        clearanceState = clearance,
        onboardingComplete = true,
    )

    fun finishOnboarding() {
        val redFlag = heartCondition || chestPain || doctorAdvisedAgainst
        val health = buildHealthProfile(
            if (redFlag) ClearanceState.REQUIRED else ClearanceState.NOT_REQUIRED,
        )
        // The full gate: health screen + completed diet screen + named coach.
        val goalProfile = buildGoalProfile()
        val preferences = buildPreferences()
        val completion = OnboardingGate.evaluateCompletion(goalProfile, health, preferences)
        gateResult = completion.healthGate
        repository.saveOnboarding(goalProfile, health, preferences)
        if (completion.healthGate.planGenerationBlocked) {
            step = OnboardingStep.CLEARANCE
        } else {
            onFinished()
        }
    }

    val bodyStatsValid = ageText.toIntOrNull() != null &&
        heightText.toDoubleOrNull() != null &&
        weightText.toDoubleOrNull() != null

    Scaffold(
        topBar = { TopAppBar(title = { Text("Forge — set up") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            when (step) {
                OnboardingStep.GOAL -> {
                    SectionTitle("Your goal")
                    Text(
                        "Forge builds every plan from this. Nothing is generated until setup is complete.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ChipRow(
                        label = "Goal",
                        options = GoalType.entries,
                        selected = goal,
                        optionLabel = { it.goalLabel() },
                        onSelect = { goal = it },
                    )
                    Text("Training days per week", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { if (trainingDays > 1) trainingDays-- }) { Text("−") }
                        Text(
                            "$trainingDays",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                        OutlinedButton(onClick = { if (trainingDays < 7) trainingDays++ }) { Text("+") }
                    }
                    ChipRow(
                        label = "Experience",
                        options = ExperienceLevel.entries,
                        selected = experience,
                        optionLabel = { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
                        onSelect = { experience = it },
                    )
                    ChipRow(
                        label = "Equipment access",
                        options = EquipmentAccess.entries,
                        selected = equipment,
                        optionLabel = { it.equipmentLabel() },
                        onSelect = { equipment = it },
                    )
                    ContinueButton("Continue") { step = OnboardingStep.BODY }
                }

                OnboardingStep.BODY -> {
                    SectionTitle("Body stats")
                    Text(
                        "Used by the calorie and protein formulas — never turned into a BMI score (Forge doesn't use BMI).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    NumberField("Age (years)", ageText, KeyboardType.Number) { ageText = it }
                    NumberField("Height (cm)", heightText, KeyboardType.Decimal) { heightText = it }
                    NumberField("Weight (kg)", weightText, KeyboardType.Decimal) { weightText = it }
                    ChipRow(
                        label = "Sex",
                        options = Sex.entries,
                        selected = sex,
                        optionLabel = { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
                        onSelect = { sex = it },
                    )
                    ChipRow(
                        label = "Activity level",
                        options = ActivityLevel.entries,
                        selected = activity,
                        optionLabel = { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
                        onSelect = { activity = it },
                    )
                    ContinueButton("Continue", enabled = bodyStatsValid) { step = OnboardingStep.HEALTH }
                }

                OnboardingStep.HEALTH -> {
                    SectionTitle("Health screen")
                    Text(
                        "Answer honestly — these become hard constraints on every plan Forge generates. " +
                            "Forge applies the constraints; it never diagnoses anything.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CheckRow("Diagnosed heart condition", heartCondition) { heartCondition = it }
                    CheckRow("High blood pressure", highBloodPressure) { highBloodPressure = it }
                    CheckRow("Chest pain during activity", chestPain) { chestPain = it }
                    CheckRow("A doctor has advised me not to exercise", doctorAdvisedAgainst) { doctorAdvisedAgainst = it }
                    CheckRow("Asthma or breathing limits", asthma) { asthma = it }
                    CheckRow("Diabetes", diabetes) { diabetes = it }
                    CheckRow("Medication that affects heart rate (e.g. beta blockers)", hrMedication) { hrMedication = it }

                    Text("Joint or bone problems", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        JointArea.entries.forEach { area ->
                            FilterChip(
                                selected = area in jointLimitations,
                                onClick = {
                                    jointLimitations = if (area in jointLimitations) {
                                        jointLimitations - area
                                    } else {
                                        jointLimitations + area
                                    }
                                },
                                label = { Text(area.name.lowercase().replace('_', ' ').replaceFirstChar { c -> c.uppercase() }) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = injuriesText,
                        onValueChange = { injuriesText = it },
                        label = { Text("Past injuries or surgeries (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = freeTextLimitations,
                        onValueChange = { freeTextLimitations = it },
                        label = { Text("Anything else that limits movement (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ContinueButton("Continue") { step = OnboardingStep.DIET }
                }

                OnboardingStep.DIET -> {
                    SectionTitle("Diet")
                    OutlinedTextField(
                        value = dietaryPatternText,
                        onValueChange = { dietaryPatternText = it },
                        label = { Text("Dietary pattern (e.g. vegetarian) — optional") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = allergiesText,
                        onValueChange = { allergiesText = it },
                        label = { Text("Allergies, comma-separated") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Allergies are hard constraints: generated meal plans are checked against them and never include them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = dislikesText,
                        onValueChange = { dislikesText = it },
                        label = { Text("Foods you won't eat, comma-separated — optional") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = cuisinesText,
                        onValueChange = { cuisinesText = it },
                        label = { Text("Cuisines you like, comma-separated — optional") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Leaving these blank is a valid answer — finishing this screen is what counts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ContinueButton("Continue") { step = OnboardingStep.COACH }
                }

                OnboardingStep.COACH -> {
                    SectionTitle("Meet your coach")
                    Text(
                        "Your AI Coach needs a name and a tone before its first briefing. " +
                            "Both can be changed later in Settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = coachNameText,
                        onValueChange = { coachNameText = it },
                        label = { Text("Coach name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Suggestions", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Forge", "Atlas", "Nova", "Coach K").forEach { suggestion ->
                            FilterChip(
                                selected = coachNameText.trim() == suggestion,
                                onClick = { coachNameText = suggestion },
                                label = { Text(suggestion) },
                            )
                        }
                    }
                    ChipRow(
                        label = "Tone",
                        options = CoachTone.entries,
                        selected = coachTone,
                        optionLabel = { it.toneLabel() },
                        onSelect = { coachTone = it },
                    )
                    ContinueButton("Finish setup", enabled = coachNameText.isNotBlank()) { finishOnboarding() }
                }

                OnboardingStep.CLEARANCE -> {
                    SectionTitle("Medical clearance needed first")
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Your health screen raised a red flag:",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            gateResult?.redFlagsPresent?.forEach { flag ->
                                Text("• $flag")
                            }
                            Text(
                                "Forge won't generate workouts or plans until a clinician has cleared you for exercise. " +
                                    "This is a safety gate, not a diagnosis. Once you have clearance, confirm it below — " +
                                    "your limitations still shape every plan afterwards.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Button(
                        onClick = {
                            repository.healthProfile?.let { current ->
                                repository.updateHealthProfile(
                                    current.copy(clearanceState = ClearanceState.OBTAINED),
                                )
                            }
                            onFinished()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("I have obtained medical clearance")
                    }
                    OutlinedButton(
                        onClick = { step = OnboardingStep.HEALTH },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Go back and review my answers")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall)
}

@Composable
private fun ContinueButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(text)
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    keyboardType: KeyboardType,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(optionLabel(option)) },
            )
        }
    }
}

private fun splitList(text: String): List<String> =
    text.split(",").map { it.trim() }.filter { it.isNotEmpty() }

private fun GoalType.goalLabel(): String = when (this) {
    GoalType.BUILD_MUSCLE -> "Build muscle"
    GoalType.LOSE_FAT -> "Lose fat"
    GoalType.RECOMP -> "Recomp"
    GoalType.MAINTAIN -> "Maintain"
}

private fun EquipmentAccess.equipmentLabel(): String = when (this) {
    EquipmentAccess.FULL_GYM -> "Full gym"
    EquipmentAccess.DUMBBELLS_ONLY -> "Dumbbells only"
    EquipmentAccess.HOME_MINIMAL -> "Home / minimal"
}

private fun CoachTone.toneLabel(): String = when (this) {
    CoachTone.SUPPORTIVE -> "Supportive"
    CoachTone.DRILL_SERGEANT -> "Drill sergeant"
    CoachTone.STRAIGHT_DATA -> "Straight data"
}
