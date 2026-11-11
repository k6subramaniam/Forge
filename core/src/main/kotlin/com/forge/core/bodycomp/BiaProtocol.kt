package com.forge.core.bodycomp

import java.time.LocalDateTime
import java.time.LocalTime

/** What the user confirmed on the prompt shown before a watch BIA reading. */
data class BiaConditions(
    val fasted: Boolean,
    val postVoid: Boolean,
    /** No unusual fluid or alcohol since yesterday, and not dehydrated. */
    val usualHydration: Boolean,
)

/** One watch bioimpedance reading, timed in the user's local wall-clock time. */
data class BiaReading(
    val takenAt: LocalDateTime,
    val fatPercent: Double,
    val conditions: BiaConditions,
)

/** One condition of the reading protocol, with the prompt text that asks for it. */
enum class BiaCheck(val prompt: String) {
    MORNING("Take it in the morning"),
    FASTED("Before eating or drinking"),
    POST_VOID("After using the bathroom"),
    USUAL_HYDRATION("At your usual hydration — no unusual fluids or alcohol since yesterday"),
}

/** The QC grade of one reading: it enters the trend only when every check passed. */
data class BiaQcResult(
    val reading: BiaReading,
    val failedChecks: List<BiaCheck>,
) {
    val acceptedIntoTrend: Boolean get() = failedChecks.isEmpty()
}

/**
 * Procedure control for watch BIA. BIA is procedure-sensitive: timing,
 * fasting and hydration decide whether readings repeat (Campa et al. 2021,
 * Evidence Base v3 §A2), so a reading enters the trend only under consistent
 * conditions (v3 change 2). A failed reading is kept with its reasons, never
 * silently dropped, so the UI can say why it didn't count.
 */
object BiaProtocol {

    /** "Morning" in local time: from 04:00 up to, not including, noon. */
    val MORNING_START: LocalTime = LocalTime.of(4, 0)
    val MORNING_END: LocalTime = LocalTime.NOON

    /** The checklist to show before a reading, in order. */
    val checklist: List<BiaCheck> get() = BiaCheck.entries

    fun grade(reading: BiaReading): BiaQcResult {
        val time = reading.takenAt.toLocalTime()
        val failed = buildList {
            if (time < MORNING_START || time >= MORNING_END) add(BiaCheck.MORNING)
            if (!reading.conditions.fasted) add(BiaCheck.FASTED)
            if (!reading.conditions.postVoid) add(BiaCheck.POST_VOID)
            if (!reading.conditions.usualHydration) add(BiaCheck.USUAL_HYDRATION)
        }
        return BiaQcResult(reading, failed)
    }

    /** The readings that passed QC, as ensemble members with the method's accuracy. */
    fun acceptedEstimates(
        readings: List<BiaReading>,
        accuracy: MethodAccuracy = MethodAccuracy.PROVISIONAL,
    ): List<BodyFatEstimate> =
        readings.map(::grade)
            .filter { it.acceptedIntoTrend }
            .map {
                BodyFatEstimate(
                    method = CompositionMethod.WRIST_BIA,
                    date = it.reading.takenAt.toLocalDate(),
                    fatPercent = it.reading.fatPercent,
                    standardErrorPp = accuracy.of(CompositionMethod.WRIST_BIA),
                )
            }
}
