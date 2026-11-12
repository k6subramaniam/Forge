package com.forge.core.sync

import com.forge.core.model.PRRecord
import com.forge.core.training.LiveSessionState
import com.forge.core.training.RestTimerState
import com.forge.core.training.SessionEngine
import java.time.Instant

/** One thing the lifter did during a session, as recorded in a [SessionJournal]. */
sealed interface SessionCommand {
    data class LogSet(
        val weightKg: Double,
        val reps: Int,
        val at: Instant,
        val painReported: Boolean = false,
    ) : SessionCommand

    data object UndoLastSet : SessionCommand

    data object SkipExercise : SessionCommand

    /** Adds a menu exercise to the session. */
    data class AddExercise(val exerciseId: String) : SessionCommand
}

/** The session after one command, and the rest to take when the command was a logged set. */
data class JournalStep(val state: LiveSessionState, val rest: RestTimerState?)

/**
 * A live session as its starting point plus the commands applied since.
 *
 * The watch keeps this on disk instead of the session state itself. The
 * engine is a pure function of its inputs, so replaying the commands
 * rebuilds exactly the same session after the watch kills the app mid-workout
 * (blueprint 3.1: sessions run phone-free; a lost workout is the worst
 * outcome). The journal holds its own copy of the plan, menu and records it
 * started from, so a newer plan arriving mid-session can't change the replay.
 */
data class SessionJournal(
    val sessionId: String,
    val startTime: Instant,
    /** The routine being followed, or null for a freeform session. */
    val plan: WorkoutPlan?,
    /** Exercises that may be added ([SessionCommand.AddExercise] looks them up here). */
    val menu: List<MenuExercise>,
    /** Records to call PRs against at finish. */
    val existingPrs: List<PRRecord>,
    val commands: List<SessionCommand> = emptyList(),
) {
    /** The session as it stands. */
    fun replay(): LiveSessionState = commands.fold(start()) { state, command -> apply(state, command).state }

    /**
     * Applies [command] to [state], the result of [replay], and returns the
     * new state with the journal that records it. Commands the engine rejects
     * throw, and are not recorded.
     */
    fun record(state: LiveSessionState, command: SessionCommand): Pair<SessionJournal, JournalStep> {
        val step = apply(state, command)
        return copy(commands = commands + command) to step
    }

    private fun start(): LiveSessionState =
        plan?.start(sessionId, startTime) ?: SessionEngine.startFreeform(sessionId, startTime)

    private fun apply(state: LiveSessionState, command: SessionCommand): JournalStep = when (command) {
        is SessionCommand.LogSet -> SessionEngine.logSet(
            state,
            weightKg = command.weightKg,
            reps = command.reps,
            timestamp = command.at,
            painReported = command.painReported,
        ).let { JournalStep(it.state, it.rest) }
        SessionCommand.UndoLastSet -> JournalStep(SessionEngine.undoLastSet(state), null)
        SessionCommand.SkipExercise -> JournalStep(SessionEngine.skipExercise(state), null)
        is SessionCommand.AddExercise -> {
            val item = requireNotNull(menu.firstOrNull { it.exercise.id == command.exerciseId }) {
                "'${command.exerciseId}' isn't on this session's menu"
            }
            // The menu only lists exercises the limitations allow (WatchPlanner).
            JournalStep(SessionEngine.addExercise(state, item.prescription()), null)
        }
    }
}
