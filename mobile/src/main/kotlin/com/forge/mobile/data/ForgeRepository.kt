package com.forge.mobile.data

import com.forge.core.model.GoalProfile
import com.forge.core.model.HealthProfile
import com.forge.core.model.Routine
import com.forge.core.model.UserPreferences
import com.forge.core.model.SetEntry
import com.forge.core.model.WorkoutSession
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Phase 1 in-memory repository stub. Everything is process-local: kill the app and
 * onboarding/history reset. That is deliberate for the scaffold — the shape of the
 * API is what the UI is wired against.
 *
 * TODO(Phase 2): back this with Room on the phone (blueprint §4: phone is the source
 * of truth). The HealthProfile must live in an encrypted store (SQLCipher or
 * EncryptedFile + Android Keystore) and stay out of exports unless Kumar opts in
 * (blueprint 3.0/3.6).
 * TODO(Phase 1.5): merge watch sessions arriving over the Wearable Data Layer into
 * [addSession], then write completed sessions out via HealthConnectWriter.
 */
class ForgeRepository {

    var goalProfile: GoalProfile? = null
        private set

    var healthProfile: HealthProfile? = null
        private set

    /** Preferences from onboarding (units, coach, AI mode) — defaults until setup completes. */
    var userPreferences: UserPreferences = UserPreferences.defaults()
        private set

    private val sessions = mutableListOf<WorkoutSession>()
    private val sets = mutableListOf<SetEntry>()

    // Routines and the schedule start empty and fill with the user's own
    // data (created in the routine builder, or arriving from the watch) —
    // never with fabricated plans or history.
    val routines: List<Routine> = emptyList()
    val schedule: Map<DayOfWeek, String> = emptyMap()

    fun saveOnboarding(
        goal: GoalProfile,
        health: HealthProfile,
        preferences: UserPreferences = UserPreferences.defaults(),
    ) {
        goalProfile = goal
        healthProfile = health
        userPreferences = preferences
    }

    fun updateUserPreferences(preferences: UserPreferences) {
        userPreferences = preferences
    }

    fun updateHealthProfile(health: HealthProfile) {
        healthProfile = health
    }

    fun addSession(session: WorkoutSession, sessionSets: List<SetEntry>) {
        sessions += session
        sets += sessionSets
    }

    fun allSessions(): List<WorkoutSession> = sessions.sortedByDescending { it.startTime }

    fun setsFor(sessionId: String): List<SetEntry> =
        sets.filter { it.sessionId == sessionId }.sortedBy { it.setNumber }

    /** The routine scheduled for [date], per the weekly schedule (blueprint 3.2). */
    fun routineFor(date: LocalDate): Routine? =
        schedule[date.dayOfWeek]?.let { id -> routines.firstOrNull { it.id == id } }

    /** Most recent completed performance of an exercise — the watch's "ghost target" source. */
    fun lastPerformance(exerciseId: String): SetEntry? =
        sets.filter { it.exerciseId == exerciseId && it.completed }
            .maxByOrNull { it.timestamp }
}
