package com.forge.mobile

import com.forge.core.model.ClearanceState
import com.forge.core.model.HealthProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class StartDestinationTest {

    @Test
    fun `no stored profile starts onboarding`() {
        assertEquals(MobileDestination.ONBOARDING, startDestinationFor(null))
    }

    @Test
    fun `an unfinished onboarding starts onboarding again`() {
        assertEquals(MobileDestination.ONBOARDING, startDestinationFor(HealthProfile(onboardingComplete = false)))
    }

    @Test
    fun `a completed onboarding with no red flag opens Home`() {
        assertEquals(MobileDestination.HOME, startDestinationFor(HealthProfile(onboardingComplete = true)))
    }

    @Test
    fun `a red flag still awaiting clearance reopens the clearance screen after a restart`() {
        // The gate must survive persistence: closing the app on the clearance
        // screen must not land the user on Home next launch.
        val pending = HealthProfile(
            hasExertionalChestPain = true,
            clearanceState = ClearanceState.REQUIRED,
            onboardingComplete = true,
        )
        assertEquals(MobileDestination.CLEARANCE, startDestinationFor(pending))
    }

    @Test
    fun `recorded clearance opens Home`() {
        val cleared = HealthProfile(
            hasDiagnosedHeartCondition = true,
            clearanceState = ClearanceState.OBTAINED,
            onboardingComplete = true,
        )
        assertEquals(MobileDestination.HOME, startDestinationFor(cleared))
    }
}
