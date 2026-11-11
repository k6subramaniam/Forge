package com.forge.mobile.data

import com.forge.core.data.SampleData
import com.forge.core.model.GoalProfile
import com.forge.core.model.HealthProfile
import com.forge.core.model.Routine
import com.forge.core.model.SetEntry
import com.forge.core.model.WorkoutSession
import java.time.DayOfWeek
import java.time.Instant
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

    private val sessions = mutableListOf<WorkoutSession>()
    private val sets = mutableListOf<SetEntry>()

    val routines: List<Routine> = SampleData.routines
    val schedule: Map<DayOfWeek, String> = SampleData.schedule

    fun saveOnboarding(goal: GoalProfile, health: HealthProfile) {
        goalProfile = goal
        healthProfile = health
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

    init {
        seedSampleHistory()
    }

    /** Two past sessions so History and ghost targets demonstrate real data flow in Phase 1. */
    private fun seedSampleHistory() {
        val now = Instant.now()
        val pushSession = WorkoutSession(
            id = "seed-session-push",
            routineId = SampleData.pushA.id,
            routineName = SampleData.pushA.name,
            startTime = now.minusSeconds(3 * 24 * 3600),
            endTime = now.minusSeconds(3 * 24 * 3600 - 2700),
            avgHeartRateBpm = 118,
            maxHeartRateBpm = 152,
        )
        val pushSets = listOf(
            SetEntry("seed-s1", pushSession.id, SampleData.benchPress.id, SampleData.benchPress.name, 1, 60.0, 8, true, pushSession.startTime),
            SetEntry("seed-s2", pushSession.id, SampleData.benchPress.id, SampleData.benchPress.name, 2, 60.0, 8, true, pushSession.startTime),
            SetEntry("seed-s3", pushSession.id, SampleData.benchPress.id, SampleData.benchPress.name, 3, 60.0, 7, true, pushSession.startTime),
            SetEntry("seed-s4", pushSession.id, SampleData.benchPress.id, SampleData.benchPress.name, 4, 60.0, 7, true, pushSession.startTime),
            SetEntry("seed-s5", pushSession.id, SampleData.overheadPress.id, SampleData.overheadPress.name, 1, 40.0, 9, true, pushSession.startTime),
        )
        val legsSession = WorkoutSession(
            id = "seed-session-legs",
            routineId = SampleData.legsA.id,
            routineName = SampleData.legsA.name,
            startTime = now.minusSeconds(24 * 3600),
            endTime = now.minusSeconds(24 * 3600 - 3300),
            avgHeartRateBpm = 124,
            maxHeartRateBpm = 161,
        )
        val legsSets = listOf(
            SetEntry("seed-s6", legsSession.id, SampleData.squat.id, SampleData.squat.name, 1, 80.0, 6, true, legsSession.startTime),
            SetEntry("seed-s7", legsSession.id, SampleData.squat.id, SampleData.squat.name, 2, 80.0, 6, true, legsSession.startTime),
            SetEntry("seed-s8", legsSession.id, SampleData.deadlift.id, SampleData.deadlift.name, 1, 100.0, 5, true, legsSession.startTime),
        )
        addSession(pushSession, pushSets)
        addSession(legsSession, legsSets)
    }
}
