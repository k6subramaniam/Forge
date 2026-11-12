package com.forge.core.model

import com.forge.core.training.WeightUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UserPreferencesTest {

    @Test
    fun `defaults factory matches the 2026-11-11 decisions`() {
        val preferences = UserPreferences.defaults()
        assertEquals(WeightUnit.LB, preferences.weightUnit)
        assertEquals(AiMode.ON_DEVICE, preferences.aiSettings.mode)
        assertNull(preferences.coach) // not named until onboarding's coach-setup step
        assertFalse(preferences.coachConfigured)
    }

    @Test
    fun `plain constructor defaults agree with the factory`() {
        assertEquals(UserPreferences.defaults(), UserPreferences())
    }

    @Test
    fun `naming the coach completes coach setup with the supportive default tone`() {
        val preferences = UserPreferences.defaults().copy(coach = CoachProfile(name = "Forge"))
        assertTrue(preferences.coachConfigured)
        assertEquals(CoachTone.SUPPORTIVE, preferences.coach?.tone)
    }

    @Test
    fun `kg remains one toggle away`() {
        val preferences = UserPreferences.defaults().copy(weightUnit = WeightUnit.KG)
        assertEquals(WeightUnit.KG, preferences.weightUnit)
    }
}
