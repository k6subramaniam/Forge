package com.forge.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.forge.wear.theme.ForgeWearTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ForgeWearTheme {
                WearWorkoutApp()
            }
        }
    }
}

/**
 * Watch home: until a real session is loaded there is nothing to log, so the
 * watch says exactly that — no placeholder workout, no fabricated ghost target.
 * The set logger and rest timer screens stay in this module, ready to be
 * driven by the real session.
 *
 * TODO(Phase 1): drive this from a real WorkoutSession (start/freeform,
 * exercise progression, session summary with PRs from :core's PrDetector),
 * persist sets to Room on the watch, and sync to the phone over the Data
 * Layer (blueprint 3.6). The logger's ghost target then comes from the
 * phone's last-performance record for the lift — real history only.
 */
@Composable
fun WearWorkoutApp() {
    Scaffold(timeText = { TimeText() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "No workout loaded",
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Start one on your phone",
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
