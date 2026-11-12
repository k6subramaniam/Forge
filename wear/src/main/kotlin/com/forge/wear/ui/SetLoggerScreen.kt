package com.forge.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText

/** The exercise being logged, or null when there's nothing to log right now. */
data class LoggerExercise(
    val name: String,
    /** "Last time: 60 kg × 8", or null without history. */
    val ghostTarget: String?,
    val setNumber: Int,
    val targetSets: Int,
)

/**
 * Watch set logger (blueprint 3.1): exercise name, ghost target from last time,
 * large +/− steppers for weight and reps, one tap to complete the set. Below
 * those, the session controls: log a set that hurt, undo, skip, add an
 * exercise, finish. All workout logic is :core's; this screen is state in,
 * events out.
 *
 * TODO: live heart rate row via Health Services ExerciseClient, and an ongoing
 * activity so the session stays on the watch face between sets.
 */
@Composable
fun SetLoggerScreen(
    exercise: LoggerExercise?,
    weightKg: Double,
    reps: Int,
    hasLoggedSets: Boolean,
    canAddExercise: Boolean,
    onWeightChange: (deltaKg: Double) -> Unit,
    onRepsChange: (delta: Int) -> Unit,
    onCompleteSet: () -> Unit,
    onPainSet: () -> Unit,
    onUndo: () -> Unit,
    onSkip: () -> Unit,
    onAddExercise: () -> Unit,
    onFinish: () -> Unit,
) {
    val listState = rememberScalingLazyListState()
    // Finishing ends the session for good, so it takes a second tap.
    var confirmingFinish by remember { mutableStateOf(false) }

    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            if (exercise != null) {
                item { Centered(exercise.name, MaterialTheme.typography.title3) }
                exercise.ghostTarget?.let { ghost ->
                    item { Centered(ghost, MaterialTheme.typography.body2, secondary = true) }
                }
                item { Centered("Set ${exercise.setNumber} of ${exercise.targetSets}", MaterialTheme.typography.body1) }
                item {
                    StepperRow(
                        label = "kg",
                        value = formatKg(weightKg),
                        onMinus = { onWeightChange(-WEIGHT_STEP_KG) },
                        onPlus = { onWeightChange(WEIGHT_STEP_KG) },
                    )
                }
                item {
                    StepperRow(
                        label = "reps",
                        value = reps.toString(),
                        onMinus = { onRepsChange(-1) },
                        onPlus = { onRepsChange(1) },
                    )
                }
                item { WideChip("Complete set", onCompleteSet) }
                item {
                    // Pain swaps the exercise out for the rest of the session (blueprint 3.0).
                    WideChip("Log set — it hurt", onPainSet, secondary = true)
                }
            } else {
                item {
                    Centered(
                        if (hasLoggedSets) "Planned work done." else "Add an exercise to begin.",
                        MaterialTheme.typography.title3,
                    )
                }
            }
            if (canAddExercise) item { WideChip("Add exercise", onAddExercise, secondary = true) }
            if (exercise != null) item { WideChip("Skip exercise", onSkip, secondary = true) }
            if (hasLoggedSets) item { WideChip("Undo last set", onUndo, secondary = true) }
            item {
                val label = when {
                    confirmingFinish -> "Tap again to confirm"
                    hasLoggedSets -> "Finish workout"
                    else -> "End without saving"
                }
                WideChip(label, secondary = !confirmingFinish, onClick = {
                    if (confirmingFinish) onFinish() else confirmingFinish = true
                })
            }
        }
    }
}

/** Big round − / value / + controls sized for sweaty mid-set taps (blueprint 3.1). */
@Composable
private fun StepperRow(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = onMinus) { Text("−") }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = value, style = MaterialTheme.typography.display3)
            Text(
                text = label,
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurfaceVariant,
            )
        }
        Button(onClick = onPlus) { Text("+") }
    }
}

@Composable
internal fun Centered(text: String, style: androidx.compose.ui.text.TextStyle, secondary: Boolean = false) {
    Text(
        text = text,
        style = style,
        color = if (secondary) MaterialTheme.colors.onSurfaceVariant else MaterialTheme.colors.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun WideChip(label: String, onClick: () -> Unit, secondary: Boolean = false, secondaryLabel: String? = null) {
    Chip(
        onClick = onClick,
        label = { Text(label, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        secondaryLabel = secondaryLabel?.let { { Text(it, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) } },
        colors = if (secondary) ChipDefaults.secondaryChipColors() else ChipDefaults.primaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

internal const val WEIGHT_STEP_KG = 2.5

internal fun formatKg(weightKg: Double): String =
    if (weightKg == weightKg.toInt().toDouble()) weightKg.toInt().toString() else weightKg.toString()
