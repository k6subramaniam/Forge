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
import com.forge.mobile.data.db.ForgeDatabase
import com.forge.mobile.data.db.toEntity
import com.forge.mobile.data.db.toModel
import com.forge.mobile.data.secure.HealthProfileStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.LocalDate

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
 * TODO(Phase 2): merge sessions arriving from the watch over the Wearable Data
 * Layer into [addSession], then write completed ones out via HealthConnectWriter.
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
