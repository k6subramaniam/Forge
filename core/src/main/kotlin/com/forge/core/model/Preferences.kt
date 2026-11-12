package com.forge.core.model

import com.forge.core.training.WeightUnit

/**
 * The user's app preferences, collected in onboarding (blueprint 3.0) and
 * editable afterwards in Settings. Kumar's 2026-11-11 decisions are the
 * defaults: lb units, on-device AI, supportive coach tone.
 *
 * No secrets live here by design: the cloud API key itself stays in Android
 * Keystore-backed storage in the mobile layer; [aiSettings] only records
 * whether one has been configured.
 */
data class UserPreferences(
    /** Display units only — weights are always stored canonically in kg in :core. */
    val weightUnit: WeightUnit = WeightUnit.LB,
    /** Null until onboarding's coach-setup step names the coach. */
    val coach: CoachProfile? = null,
    val aiSettings: AiSettings = AiSettings(),
) {
    /** Blueprint 3.0 step 6: coach setup is complete once a coach has been named. */
    val coachConfigured: Boolean get() = coach?.isConfigured == true

    companion object {
        /** The out-of-the-box preferences, matching the 2026-11-11 decisions. */
        fun defaults(): UserPreferences = UserPreferences()
    }
}
