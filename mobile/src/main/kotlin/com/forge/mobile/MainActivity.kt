package com.forge.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.forge.mobile.data.ForgeRepository
import com.forge.mobile.theme.ForgeTheme
import com.forge.mobile.ui.HistoryScreen
import com.forge.mobile.ui.HomeScreen
import com.forge.mobile.ui.OnboardingScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ForgeTheme {
                ForgeApp()
            }
        }
    }
}

private enum class MobileDestination { ONBOARDING, HOME, HISTORY }

/**
 * Phase 1 navigation: a simple state machine instead of a nav library — three
 * destinations don't justify one yet.
 *
 * TODO(Phase 2): when Room lands, the start destination reads the persisted
 * onboarding-complete flag; today the repository is in-memory, so a cold start
 * begins at onboarding again (blueprint 3.0: the gate is mandatory, never skipped).
 */
@Composable
fun ForgeApp() {
    val repository = remember { ForgeRepository() }
    var destination by remember {
        mutableStateOf(
            if (repository.healthProfile?.onboardingComplete == true) {
                MobileDestination.HOME
            } else {
                MobileDestination.ONBOARDING
            },
        )
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        when (destination) {
            MobileDestination.ONBOARDING -> OnboardingScreen(
                repository = repository,
                onFinished = { destination = MobileDestination.HOME },
            )
            MobileDestination.HOME -> HomeScreen(
                repository = repository,
                onOpenHistory = { destination = MobileDestination.HISTORY },
            )
            MobileDestination.HISTORY -> HistoryScreen(
                repository = repository,
                onBack = { destination = MobileDestination.HOME },
            )
        }
    }
}
