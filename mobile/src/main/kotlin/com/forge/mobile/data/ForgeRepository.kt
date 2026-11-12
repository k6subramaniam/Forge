package com.forge.mobile.data

import com.forge.core.bodycomp.BiaReading
import com.forge.core.bodycomp.DexaScan
import com.forge.core.bodycomp.TapeMeasurement
import com.forge.core.data.SampleData
import com.forge.core.model.GoalProfile
import com.forge.core.model.HealthProfile
import com.forge.core.model.Routine
import com.forge.core.model.SetEntry
import com.forge.core.model.WorkoutSession
import com.forge.core.nutrition.WeighIn
import com.forge.core.sync.WatchPlanner
import com.forge.core.sync.WatchPlans
import com.forge.core.training.CompletedSession
import com.forge.mobile.data.db.ForgeDatabase
import com.forge.mobile.data.db.toEntity
import com.forge.mobile.data.db.toModel
import com.forge.mobile.data.secure.HealthProfileStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One past session with its sets, for History. */
data class SessionHistoryItem(
    val session: WorkoutSession,
    /** In set order. */
    val sets: List<SetEntry>,
)

/**
 * The phone's data, persisted (blueprint §4: the phone is the source of truth).
 *
 * Training data (sessions, sets, goal profile, weigh-ins, body-composition
 * inputs) is stored in [ForgeDatabase]. The health screen answers are stored in
 * [HealthProfileStore], an encrypted file kept out of the database and out of
 * backups (blueprint 3.0/3.6).
 *
 * Reads that the UI shows live are Flows; everything else suspends. Routines
 * and the weekly schedule are still the built-in templates.
 *
 * Sessions logged on the watch arrive through [addSession]
 * (com.forge.mobile.sync.SessionIngest). [plans] is what the watch is sent.
 *
 * TODO(Phase 2): user-built routines move into the database with the routine builder.
 */
class ForgeRepository(
    private val database: ForgeDatabase,
    private val healthProfileStore: HealthProfileStore,
) {
    val routines: List<Routine> = SampleData.routines
    val schedule: Map<DayOfWeek, String> = SampleData.schedule

    /** The routine scheduled for [date], per the weekly schedule (blueprint 3.2). */
    fun routineFor(date: LocalDate): Routine? =
        schedule[date.dayOfWeek]?.let { id -> routines.firstOrNull { it.id == id } }

    suspend fun goalProfile(): GoalProfile? = database.goalProfileDao().get()?.toModel()

    suspend fun healthProfile(): HealthProfile? = healthProfileStore.read()

    /**
     * Saves both onboarding profiles. The goal profile is written first, so the
     * onboarding-complete flag (carried by the health profile) never lands
     * without its goal.
     */
    suspend fun saveOnboarding(goal: GoalProfile, health: HealthProfile) {
        database.goalProfileDao().upsert(goal.toEntity())
        healthProfileStore.write(health)
    }

    suspend fun updateHealthProfile(health: HealthProfile) {
        healthProfileStore.write(health)
    }

    /** Stores a session and its sets in one transaction; re-adding a session updates it. */
    suspend fun addSession(session: WorkoutSession, sets: List<SetEntry>) {
        require(sets.all { it.sessionId == session.id }) { "Every set must belong to session ${session.id}" }
        database.sessionDao().upsertSessionWithSets(session.toEntity(), sets.map { it.toEntity() })
    }

    /** Every session, newest first, each with its sets in order. Re-emits on change. */
    fun observeHistory(): Flow<List<SessionHistoryItem>> =
        database.sessionDao().observeSessionsWithSets().map { rows ->
            rows.map { row ->
                SessionHistoryItem(
                    session = row.session.toModel(),
                    sets = row.sets.sortedBy { it.setNumber }.map { it.toModel() },
                )
            }
        }

    /** Finished sessions in the read-models' shape ([HistoryReadModels]). */
    suspend fun completedSessions(): List<CompletedSession> =
        observeHistory().first()
            .filter { it.session.isCompleted }
            .map { CompletedSession(it.session, it.sets) }

    /**
     * The week of plans the watch runs, built from the stored history and
     * health profile ([WatchPlanner]). Home shows the same plan for today.
     */
    suspend fun plans(today: LocalDate, zone: ZoneId, now: Instant): WatchPlans =
        WatchPlanner.plan(
            today = today,
            routineFor = ::routineFor,
            history = completedSessions(),
            healthProfile = healthProfile(),
            zone = zone,
            generatedAt = now,
        )

    suspend fun session(id: String): WorkoutSession? = database.sessionDao().session(id)?.toModel()

    suspend fun setsFor(sessionId: String): List<SetEntry> =
        database.sessionDao().setsFor(sessionId).map { it.toModel() }

    /** Most recent completed performance of an exercise: the watch's ghost-target source. */
    suspend fun lastPerformance(exerciseId: String): SetEntry? =
        database.sessionDao().lastCompletedSet(exerciseId)?.toModel()

    /** Logs a weigh-in. A second one on the same day replaces the first. */
    suspend fun logWeighIn(weighIn: WeighIn) {
        database.weighInDao().upsert(weighIn.toEntity())
    }

    fun observeWeighIns(): Flow<List<WeighIn>> =
        database.weighInDao().observeAll().map { rows -> rows.map { it.toModel() } }

    /**
     * Stores a watch BIA reading exactly as taken. QC ([com.forge.core.bodycomp.BiaProtocol])
     * runs on read, so a failed reading is kept with its reasons.
     */
    suspend fun logBiaReading(reading: BiaReading) {
        database.bodyCompositionDao().insertBia(reading.toEntity())
    }

    suspend fun logTapeMeasurement(measurement: TapeMeasurement) {
        database.bodyCompositionDao().insertTape(measurement.toEntity())
    }

    suspend fun logDexaScan(scan: DexaScan) {
        database.bodyCompositionDao().insertDexa(scan.toEntity())
    }

    suspend fun biaReadings(): List<BiaReading> = database.bodyCompositionDao().allBia().map { it.toModel() }

    suspend fun tapeMeasurements(): List<TapeMeasurement> =
        database.bodyCompositionDao().allTape().map { it.toModel() }

    suspend fun dexaScans(): List<DexaScan> = database.bodyCompositionDao().allDexa().map { it.toModel() }
}
