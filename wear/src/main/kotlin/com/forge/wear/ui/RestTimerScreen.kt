package com.forge.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.forge.core.training.RestTimerLogic
import com.forge.core.training.RestTimerState
import kotlinx.coroutines.delay

/**
 * Rest timer (blueprint 3.1): starts automatically when a set is completed.
 * The countdown itself is :core's [RestTimerLogic] — this screen owns the clock
 * loop and renders state; +15s / skip forward straight into the state machine.
 * The watch buzzes when rest is over, so the lifter needn't watch the screen.
 */
@Composable
fun RestTimerScreen(
    initialState: RestTimerState,
    /** What comes next, e.g. "Next: Bench Press · set 2". */
    nextLabel: String,
    onRestFinished: () -> Unit,
) {
    var state by remember { mutableStateOf(initialState) }
    val latestOnFinished by rememberUpdatedState(onRestFinished)
    val buzz = rememberRestOverBuzz()
    var skipped by remember { mutableStateOf(false) }

    // One effect per state value: each tick schedules the next, pause idles the
    // effect, and reaching zero hands back after a beat (cancelled if the user
    // adds time first, because the state — and therefore this effect — changes).
    LaunchedEffect(state) {
        when {
            state.isFinished -> {
                // A skip is the lifter's own choice; only a countdown reaching zero buzzes.
                if (!skipped) buzz()
                delay(900)
                latestOnFinished()
            }
            state.isRunning -> {
                delay(1_000)
                state = RestTimerLogic.tick(state)
            }
        }
    }

    Scaffold(timeText = { TimeText() }) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "REST",
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurfaceVariant,
            )
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = state.progressFraction,
                    modifier = Modifier.size(132.dp),
                    strokeWidth = 5.dp,
                )
                Text(
                    text = formatClock(state.remainingSeconds),
                    style = MaterialTheme.typography.display2,
                )
            }
            Text(
                text = nextLabel,
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(
                    onClick = { state = RestTimerLogic.addTime(state) },
                    label = { Text("+15s") },
                )
                Chip(
                    onClick = {
                        skipped = true
                        state = RestTimerLogic.skip(state)
                    },
                    label = { Text("Skip") },
                )
            }
        }
    }
}

private fun formatClock(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
