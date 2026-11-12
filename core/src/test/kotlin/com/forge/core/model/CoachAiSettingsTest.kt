package com.forge.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CoachAiSettingsTest {

    @Test
    fun `coach defaults to the supportive tone`() {
        val coach = CoachProfile(name = "Forge")
        assertEquals(CoachTone.SUPPORTIVE, coach.tone)
        assertTrue(coach.isConfigured)
    }

    @Test
    fun `blank coach name is rejected`() {
        assertThrows<IllegalArgumentException> { CoachProfile(name = "  ") }
    }

    @Test
    fun `ai defaults to on-device`() {
        val settings = AiSettings()
        assertEquals(AiMode.ON_DEVICE, settings.mode)
        assertEquals(AiMode.ON_DEVICE, settings.effectiveMode())
        assertFalse(settings.isCloudReady)
    }

    @Test
    fun `cloud without a key falls back to on-device`() {
        val settings = AiSettings(mode = AiMode.CLOUD, cloudProvider = CloudProvider.GEMINI, cloudApiKeyConfigured = false)
        assertFalse(settings.isCloudReady)
        assertEquals(AiMode.ON_DEVICE, settings.effectiveMode())
    }

    @Test
    fun `cloud with provider and key is ready`() {
        val settings = AiSettings(mode = AiMode.CLOUD, cloudProvider = CloudProvider.CLAUDE, cloudApiKeyConfigured = true)
        assertTrue(settings.isCloudReady)
        assertEquals(AiMode.CLOUD, settings.effectiveMode())
    }

    @Test
    fun `cloud-quality features prompt when cloud is not ready`() {
        val settings = AiSettings()
        assertTrue(settings.needsCloudPromptFor(CloudQualityFeature.FULL_PROGRAM_GENERATION))
        assertTrue(settings.needsCloudPromptFor(CloudQualityFeature.PHOTO_MEAL_ANALYSIS))
        assertFalse(settings.needsCloudPromptFor(CloudQualityFeature.MORNING_BRIEF))
    }

    @Test
    fun `cloud-quality features do not prompt once cloud is ready`() {
        val settings = AiSettings(mode = AiMode.CLOUD, cloudProvider = CloudProvider.OPENAI, cloudApiKeyConfigured = true)
        assertFalse(settings.needsCloudPromptFor(CloudQualityFeature.FULL_PROGRAM_GENERATION))
        assertFalse(settings.needsCloudPromptFor(CloudQualityFeature.PHOTO_MEAL_ANALYSIS))
    }
}
