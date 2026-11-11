package com.forge.core.model

/**
 * Blueprint 3.0 step 6 / 3.8: the named AI Coach persona.
 * The name and tone are collected in onboarding and stay editable in Settings.
 */
enum class CoachTone {
    SUPPORTIVE,
    DRILL_SERGEANT,
    STRAIGHT_DATA,
}

data class CoachProfile(
    val name: String,
    val tone: CoachTone = CoachTone.SUPPORTIVE,
) {
    init {
        require(name.isNotBlank()) { "Coach name must not be blank" }
    }

    /** A coach only counts as configured once a non-blank name has been chosen. */
    val isConfigured: Boolean get() = name.isNotBlank()
}

/**
 * Blueprint 3.0 AI-mode decision (2026-11-11): on-device is the default;
 * cloud is opt-in only and activates after the user enters their own API key.
 *
 * The raw API key never lives in :core — it is stored in Android
 * Keystore-backed encrypted storage by the mobile layer. :core only
 * tracks whether a key has been configured and which provider it is for.
 */
enum class AiMode {
    ON_DEVICE,
    CLOUD,
}

enum class CloudProvider {
    GEMINI,
    CLAUDE,
    OPENAI,
}

data class AiSettings(
    val mode: AiMode = AiMode.ON_DEVICE,
    val cloudProvider: CloudProvider? = null,
    val cloudApiKeyConfigured: Boolean = false,
) {
    /** Cloud only takes effect once a provider and a key are both present. */
    val isCloudReady: Boolean
        get() = mode == AiMode.CLOUD && cloudProvider != null && cloudApiKeyConfigured

    /** The mode the app actually runs in — falls back to on-device, never to a keyless cloud call. */
    fun effectiveMode(): AiMode = if (isCloudReady) AiMode.CLOUD else AiMode.ON_DEVICE

    /**
     * Features that want cloud quality (full program generation, photo meal
     * analysis) must prompt the user to enable cloud instead of sending
     * anything silently when cloud is not ready.
     */
    fun needsCloudPromptFor(feature: CloudQualityFeature): Boolean =
        feature.requiresCloudQuality && !isCloudReady
}

enum class CloudQualityFeature(val requiresCloudQuality: Boolean) {
    FULL_PROGRAM_GENERATION(true),
    PHOTO_MEAL_ANALYSIS(true),
    MORNING_BRIEF(false),
    SESSION_DEBRIEF(false),
}
