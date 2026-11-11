package com.forge.core.training

import com.forge.core.testing.TestRoutines
import com.forge.core.model.RoutineExercise
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RestTimerTest {

    @Test
    fun `start begins running at full duration`() {
        val state = RestTimerLogic.start(90)
        assertEquals(90, state.totalSeconds)
        assertEquals(90, state.remainingSeconds)
        assertTrue(state.isRunning)
        assertFalse(state.isFinished)
        assertEquals(0f, state.progressFraction, 0.0f)
    }

    @Test
    fun `tick counts down and finishes exactly at zero`() {
        var state = RestTimerLogic.start(3)
        state = RestTimerLogic.tick(state)
        assertEquals(2, state.remainingSeconds)
        assertFalse(state.isFinished)
        state = RestTimerLogic.tick(state, elapsedSeconds = 5) // overshoot clamps at zero
        assertEquals(0, state.remainingSeconds)
        assertTrue(state.isFinished)
        assertFalse(state.isRunning)
        assertEquals(1f, state.progressFraction, 0.0f)
    }

    @Test
    fun `tick does nothing while paused or finished`() {
        val paused = RestTimerLogic.pause(RestTimerLogic.start(60))
        assertEquals(60, RestTimerLogic.tick(paused).remainingSeconds)
        val finished = RestTimerLogic.skip(RestTimerLogic.start(60))
        assertEquals(0, RestTimerLogic.tick(finished).remainingSeconds)
    }

    @Test
    fun `progress fraction is halfway at half time`() {
        val state = RestTimerLogic.tick(RestTimerLogic.start(100), elapsedSeconds = 50)
        assertEquals(0.5f, state.progressFraction, 0.001f)
    }

    @Test
    fun `addTime extends total and remaining by 15 seconds by default`() {
        val state = RestTimerLogic.tick(RestTimerLogic.start(90), elapsedSeconds = 30)
        val extended = RestTimerLogic.addTime(state)
        assertEquals(105, extended.totalSeconds)
        assertEquals(75, extended.remainingSeconds)
        assertTrue(extended.isRunning)
    }

    @Test
    fun `addTime revives a finished timer`() {
        val finished = RestTimerLogic.skip(RestTimerLogic.start(60))
        val revived = RestTimerLogic.addTime(finished)
        assertFalse(revived.isFinished)
        assertEquals(15, revived.remainingSeconds)
        assertTrue(revived.isRunning)
    }

    @Test
    fun `skip finishes immediately`() {
        val state = RestTimerLogic.skip(RestTimerLogic.start(120))
        assertTrue(state.isFinished)
        assertEquals(0, state.remainingSeconds)
        assertFalse(state.isRunning)
    }

    @Test
    fun `pause and resume round-trip`() {
        val running = RestTimerLogic.start(60)
        val paused = RestTimerLogic.pause(running)
        assertFalse(paused.isRunning)
        assertFalse(paused.isFinished)
        val resumed = RestTimerLogic.resume(paused)
        assertTrue(resumed.isRunning)
    }

    @Test
    fun `startFor uses the routine prescription's rest seconds`() {
        val target = RoutineExercise(
            exercise = TestRoutines.benchPress,
            targetSets = 4,
            repRange = 6..8,
            targetWeightKg = 60.0,
            restSeconds = 120,
        )
        val state = RestTimerLogic.startFor(target)
        assertEquals(120, state.totalSeconds)
    }

    @Test
    fun `start rejects non-positive durations`() {
        assertThrows<IllegalArgumentException> { RestTimerLogic.start(0) }
    }
}
