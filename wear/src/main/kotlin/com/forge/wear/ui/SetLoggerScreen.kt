package com.forge.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState

/**
 * Watch set logger (blueprint 3.1): exercise name, ghost target from last time,
 * large +/− steppers for weight and reps, one tap to complete the set.
 * All workout math lives in :core; this screen is state in / events out.
 *
 * TODO(Phase 1): live heart rate row via Health Services ExerciseClient, and keep
 * the screen awake during the session (ambient/always-on handling).
 */
@Composable
fun SetLoggerScreen(
    exerciseName: String,
    ghostTarget: String,
    setNumber: Int,
    targetSets: Int,
    weightKg: Double,
    reps: Int,
    onWeightChange: (Double) -> Unit,
    onRepsChange: (Int) -> Unit,
    onCompleteSet: () -> Unit,
) {
    val listState = rememberScalingLazyListState()

    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Text(
                    text = exerciseName,
                    style = MaterialTheme.typography.title3,
                    textAlign = TextAlign.Center,
                )
            }
            item {
                Text(
                    text = ghostTarget,
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            item {
                Text(
                    text = "Set $setNumber of $targetSets",
                    style = MaterialTheme.typography.body1,
                    textAlign = TextAlign.Center,
                )
            }
            item {
                StepperRow(
                    label = "kg",
                    value = formatWeight(weightKg),
                    onMinus = { onWeightChange((weightKg - 2.5).coerceAtLeast(0.0)) },
                    onPlus = { onWeightChange(weightKg + 2.5) },
                )
            }
            item {
                StepperRow(
                    label = "reps",
                    value = reps.toString(),
                    onMinus = { onRepsChange((reps - 1).coerceAtLeast(1)) },
                    onPlus = { onRepsChange(reps + 1) },
                )
            }
            item {
                Chip(
                    onClick = onCompleteSet,
                    label = {
                        Text(
                            text = "Complete set",
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
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

private fun formatWeight(weightKg: Double): String =
    if (weightKg == weightKg.toInt().toDouble()) {
        weightKg.toInt().toString()
    } else {
        weightKg.toString()
    }
