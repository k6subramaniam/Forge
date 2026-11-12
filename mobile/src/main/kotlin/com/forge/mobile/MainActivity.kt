package com.forge.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.forge.core.model.HealthProfile
import com.forge.core.onboarding.OnboardingGate
import com.forge.mobile.data.ForgeRepository
import com.forge.mobile.theme.ForgeTheme
import com.forge.mobile.ui.HistoryScreen
import com.forge.mobile.ui.HomeScreen
import com.forge.mobile.ui.OnboardingScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as ForgeApplication).repository
        setContent {
            ForgeTheme {
                ForgeApp(repository)
            }
        }
    }
}

internal enum class MobileDestination { ONBOARDING, CLEARANCE, HOME, HISTORY }

/**
 * Where a launch starts, from the stored health profile. Home is reachable only
 * once onboarding is complete AND the gate isn't blocking. A red flag still
 * awaiting clearance reopens the clearance screen, even after a restart
 * (blueprint 3.0: the gate is mandatory, never skipped).
 */
internal fun startDestinationFor(profile: HealthProfile?): MobileDestination = when {
    profile == null || !profile.onboardingComplete -> MobileDestination.ONBOARDING
    OnboardingGate.evaluate(profile).planGenerationBlocked -> MobileDestination.CLEARANCE
    else -> MobileDestination.HOME
}

/**
 * Phase 1 navigation: a simple state machine instead of a nav library — four
 * destinations don't justify one yet.
 *
 * The start destination comes from the persisted health profile (see
 * [startDestinationFor]). Nothing is shown until that read finishes, so the
 * onboarding screen never flashes for a user who has already completed it.
 */
@Composable
fun ForgeApp(repository: ForgeRepository) {
    var destination by remember { mutableStateOf<MobileDestination?>(null) }
    var storedProfile by remember { mutableStateOf<HealthProfile?>(null) }

    LaunchedEffect(repository) {
        if (destination == null) {
            val profile = repository.healthProfile()
            storedProfile = profile
            destination = startDestinationFor(profile)
        }
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        when (destination) {
            null -> Unit // reading the stored profile
            MobileDestination.ONBOARDING -> OnboardingScreen(
                repository = repository,
                onFinished = { destination = MobileDestination.HOME },
            )
            MobileDestination.CLEARANCE -> OnboardingScreen(
                repository = repository,
                onFinished = { destination = MobileDestination.HOME },
                pendingClearance = storedProfile?.let(OnboardingGate::evaluate),
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
