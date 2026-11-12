package com.forge.wear.workout

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.forge.core.model.PRRecord
import com.forge.core.model.SetEntry
import com.forge.core.sync.ForgeWire
import com.forge.core.sync.MenuExercise
import com.forge.core.sync.PersonalRecords
import com.forge.core.sync.PlanLookup
import com.forge.core.sync.SessionCommand
import com.forge.core.sync.SessionJournal
import com.forge.core.sync.WatchPlans
import com.forge.core.sync.WorkoutPlan
import com.forge.core.training.LiveSessionState
import com.forge.core.training.RestTimerState
import com.forge.core.training.SessionEngine
import com.forge.core.training.SessionExerciseState
import com.forge.core.training.SessionSummary
import com.forge.wear.sync.SessionStore
import com.forge.wear.sync.WatchLink
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Executors

/** What the watch shows. */
sealed interface WatchUi {
    data object Loading : WatchUi

    /** No session running. [plans] is null until the phone has sent any. */
    data class Idle(val plans: WatchPlans?, val today: LocalDate) : WatchUi {
        val lookup: PlanLookup? get() = plans?.lookup(today)
        val canStartFreeform: Boolean
            get() = plans != null && plans.blockedReason == null && plans.menu.isNotEmpty()
    }

    data class Active(
        val state: LiveSessionState,
        /** The set about to be logged, as adjusted on the steppers. */
        val weightKg: Double,
        val reps: Int,
        /** Last time's best set for the current exercise. */
        val ghost: SetEntry?,
        /** Set right after logging a set; the rest screen shows until it's done. */
        val rest: RestTimerState?,
        val menu: List<MenuExercise>,
    ) : WatchUi {
        val current: SessionExerciseState? get() = state.currentExercise
        val hasLoggedSets: Boolean get() = state.allSets.isNotEmpty()
    }

    /** [handedOver] once the session is with the Data Layer, which delivers it to the phone. */
    data class Finished(val sessionId: String, val summary: SessionSummary, val handedOver: Boolean) : WatchUi
}

/**
 * The watch's live session (blueprint 3.1). All workout logic is :core's
 * [SessionEngine], driven through a [SessionJournal]. This class holds the
 * screen state and makes sure no workout is ever lost:
 *
 * - Every command is journaled to disk before the next can be made, on one
 *   background thread so writes land in order. Reopening the app after the
 *   system killed it resumes the session exactly where it was.
 * - Finishing writes the session to the outbox, then clears the journal, then
 *   hands the session to the Data Layer. It stays in the outbox until that
 *   succeeds, and is retried on the next launch.
 */
class WorkoutViewModel(app: Application) : AndroidViewModel(app) {

    private val link = WatchLink(Wearable.getDataClient(app))
    private val store = SessionStore(app.filesDir)
    private val clock: Clock = Clock.systemDefaultZone()

    // One thread for disk and Data Layer work: writes run in the order made,
    // and outlive this ViewModel so a last write isn't cancelled on close.
    private val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val ioScope = CoroutineScope(SupervisorJob() + io)

    private val _ui = MutableStateFlow<WatchUi>(WatchUi.Loading)
    val ui: StateFlow<WatchUi> = _ui.asStateFlow()

    private var plans: WatchPlans? = null
    /** PRs earned on this watch since install; merged into every session's baseline. */
    private var localRecords: List<PRRecord> = emptyList()
    private var journal: SessionJournal? = null
    private var state: LiveSessionState? = null
    private var weightKg = 0.0
    private var reps = 1
    /** The exercise index the stepper values belong to. */
    private var draftFor: Int? = null

    init {
        viewModelScope.launch {
            localRecords = withContext(io) { store.readRecords() }
            val saved = withContext(io) { store.readJournal() }
            if (saved != null) resume(saved) else showIdle()
            ioScope.launch { deliverOutbox() }
            link.plans().collect { latest ->
                if (latest != null) plans = latest
                if (_ui.value is WatchUi.Idle || _ui.value is WatchUi.Loading) showIdle()
            }
        }
    }

    // -- Starting ---------------------------------------------------------------

    /** Starts [plan], or a freeform session when [plan] is null. */
    fun startSession(plan: WorkoutPlan?) {
        val current = plans ?: return
        if (current.blockedReason != null || journal != null) return
        if (plan != null && plan.date != LocalDate.now(clock)) {
            // The screen was showing another day's plan (the app stayed open
            // past midnight): show today's instead of starting the wrong one.
            showIdle()
            return
        }
        val started = SessionJournal(
            sessionId = "watch-${UUID.randomUUID()}",
            startTime = now(),
            plan = plan,
            menu = current.menu,
            // The phone's records plus any earned on the watch since it last
            // sent plans: several offline sessions mustn't each beat the same
            // stale record.
            existingPrs = PersonalRecords.best(current.existingPrs + localRecords),
        )
        journal = started
        state = started.replay()
        save(started)
        draftFor = null
        syncDraft()
        showActive(rest = null)
    }

    private fun resume(saved: SessionJournal) {
        val replayed = try {
            saved.replay()
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't replay the saved session", e)
            showIdle()
            return
        }
        journal = saved
        state = replayed
        draftFor = null
        syncDraft()
        showActive(rest = null)
    }

    // -- During the session -----------------------------------------------------

    fun adjustWeight(deltaKg: Double) {
        weightKg = (weightKg + deltaKg).coerceAtLeast(0.0)
        showActive(rest = null)
    }

    fun adjustReps(delta: Int) {
        reps = (reps + delta).coerceAtLeast(1)
        showActive(rest = null)
    }

    fun completeSet(painReported: Boolean = false) =
        apply(SessionCommand.LogSet(weightKg, reps, now(), painReported))

    fun undoLastSet() = apply(SessionCommand.UndoLastSet)

    fun skipExercise() = apply(SessionCommand.SkipExercise)

    fun addExercise(exerciseId: String) = apply(SessionCommand.AddExercise(exerciseId))

    fun restFinished() = showActive(rest = null)

    private fun apply(command: SessionCommand) {
        val j = journal ?: return
        val s = state ?: return
        val (next, step) = try {
            j.record(s, command)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Engine refused $command", e)
            return
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Engine refused $command", e)
            return
        }
        journal = next
        state = step.state
        save(next)
        syncDraft()
        showActive(step.rest)
    }

    /** On moving to another exercise, the steppers start at its last logged set, else its target. */
    private fun syncDraft() {
        val s = state ?: return
        if (s.currentExerciseIndex == draftFor) return
        draftFor = s.currentExerciseIndex
        val current = s.currentExercise ?: return
        val last = current.loggedSets.lastOrNull()
        weightKg = last?.weightKg ?: current.prescription.targetWeightKg
        reps = last?.reps ?: current.prescription.repRange.last
    }

    // -- Finishing ----------------------------------------------------------------

    /** Ends the session. With nothing logged it's discarded instead of sent. */
    fun finishSession() {
        val s = state ?: return
        val j = journal ?: return
        journal = null
        state = null
        if (s.allSets.isEmpty()) {
            save(null)
            showIdle()
            return
        }
        val end = maxOf(now(), s.session.startTime.plusMillis(1))
        val finished = SessionEngine.finish(s, end, LocalDate.now(clock), j.existingPrs)
        val id = finished.session.id
        val payload = ForgeWire.encodeSession(finished.session, finished.sets)
        _ui.value = WatchUi.Finished(id, finished.summary, handedOver = false)
        localRecords = PersonalRecords.best(localRecords + finished.summary.newPrs)
        val records = localRecords
        ioScope.launch {
            store.writeRecords(records)
            // On disk in the outbox before the journal goes: the session always exists somewhere.
            store.addToOutbox(id, payload)
            store.writeJournal(null)
            val handedOver = id in deliverOutbox()
            _ui.update { if (it is WatchUi.Finished && it.sessionId == id) it.copy(handedOver = handedOver) else it }
        }
    }

    fun dismissSummary() = showIdle()

    /**
     * Called whenever the app comes to the front. With no session running,
     * "today" is recomputed: the app can stay alive overnight, and the next
     * morning must offer that morning's plan from the week the watch holds.
     */
    fun onResume() {
        if (_ui.value is WatchUi.Idle) showIdle()
    }

    /** Hands every outbox session to the Data Layer; returns the ids that went. Runs on [io]. */
    private suspend fun deliverOutbox(): Set<String> =
        store.outbox()
            .filter { (id, payload) -> link.sendSession(id, payload) }
            .onEach { (id, _) -> store.removeFromOutbox(id) }
            .map { it.first }
            .toSet()

    // -- Plumbing -------------------------------------------------------------------

    private fun save(j: SessionJournal?) {
        ioScope.launch { store.writeJournal(j) }
    }

    private fun showIdle() {
        _ui.value = WatchUi.Idle(plans, LocalDate.now(clock))
    }

    private fun showActive(rest: RestTimerState?) {
        val s = state ?: return
        val j = journal ?: return
        val exerciseId = s.currentExercise?.exercise?.id
        val ghost = exerciseId?.let { id ->
            j.plan?.exercises?.firstOrNull { it.prescription.exercise.id == id }?.lastTopSet
                ?: j.menu.firstOrNull { it.exercise.id == id }?.lastTopSet
        }
        _ui.value = WatchUi.Active(s, weightKg, reps, ghost, rest, j.menu)
    }

    /** Millisecond precision: what the phone stores, so both show the same times. */
    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MILLIS)

    override fun onCleared() {
        // Lets queued writes finish; nothing new is started after this.
        io.close()
    }

    private companion object {
        const val TAG = "WorkoutViewModel"
    }
}
