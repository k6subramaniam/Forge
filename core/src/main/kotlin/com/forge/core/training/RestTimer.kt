package com.forge.core.training

import com.forge.core.model.RoutineExercise

/** Immutable rest-timer state (blueprint 3.1). The watch UI renders this and forwards events. */
data class RestTimerState(
    val totalSeconds: Int,
    val remainingSeconds: Int,
    val isRunning: Boolean,
    val isFinished: Boolean,
) {
    val elapsedSeconds: Int get() = totalSeconds - remainingSeconds

    /** 0f when the rest starts, 1f when it ends. */
    val progressFraction: Float
        get() = if (totalSeconds <= 0) {
            1f
        } else {
            (elapsedSeconds.toFloat() / totalSeconds.toFloat()).coerceIn(0f, 1f)
        }
}

/**
 * Pure rest-timer transitions, so the timer behaviour is fully unit-testable and the
 * Wear OS screen stays a thin shell (start on set completion, +15s, skip, haptic at zero —
 * the haptic itself is a UI side effect triggered by [RestTimerState.isFinished]).
 */
object RestTimerLogic {

    /** The "+15s" button on the watch (blueprint 3.1). */
    const val QUICK_ADD_SECONDS = 15

    fun start(durationSeconds: Int): RestTimerState {
        require(durationSeconds > 0) { "Rest duration must be positive, was $durationSeconds" }
        return RestTimerState(
            totalSeconds = durationSeconds,
            remainingSeconds = durationSeconds,
            isRunning = true,
            isFinished = false,
        )
    }

    /** Default rest duration comes from the exercise's routine prescription (blueprint 3.1). */
    fun startFor(routineExercise: RoutineExercise): RestTimerState = start(routineExercise.restSeconds)

    fun tick(state: RestTimerState, elapsedSeconds: Int = 1): RestTimerState {
        if (!state.isRunning || state.isFinished) return state
        val remaining = (state.remainingSeconds - elapsedSeconds).coerceAtLeast(0)
        return state.copy(
            remainingSeconds = remaining,
            isRunning = remaining > 0,
            isFinished = remaining == 0,
        )
    }

    /** Extends the rest. Also revives a finished timer (a common mid-rest change of mind). */
    fun addTime(state: RestTimerState, seconds: Int = QUICK_ADD_SECONDS): RestTimerState {
        require(seconds > 0) { "Added time must be positive, was $seconds" }
        return state.copy(
            totalSeconds = state.totalSeconds + seconds,
            remainingSeconds = state.remainingSeconds + seconds,
            isRunning = true,
            isFinished = false,
        )
    }

    fun skip(state: RestTimerState): RestTimerState =
        state.copy(remainingSeconds = 0, isRunning = false, isFinished = true)

    fun pause(state: RestTimerState): RestTimerState =
        if (state.isFinished) state else state.copy(isRunning = false)

    fun resume(state: RestTimerState): RestTimerState =
        if (state.isFinished || state.remainingSeconds == 0) state else state.copy(isRunning = true)
}
