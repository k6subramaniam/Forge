package com.forge.core.readiness

import com.forge.core.data.SampleData
import com.forge.core.model.Exercise
import com.forge.core.model.MuscleGroup
import com.forge.core.training.CompletedSession
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Where a muscle group's weekly volume sits against its landmarks. */
enum class VolumeBand {
    /** Below the maintenance floor — the group is being under-trained or rested. */
    BELOW_MAINTENANCE,

    /** At/above maintenance, below the productive range. */
    MAINTENANCE,

    /** Inside the productive range (the MAV band). */
    PRODUCTIVE,

    /** Above the productive range, at/below the recoverable ceiling (MRV). */
    HIGH,

    /** Beyond the recoverable ceiling — more than can be productively recovered from. */
    ABOVE_LANDMARKS,
}

/**
 * One muscle group's landmarks, in sets per week: [maintenanceVolume] (MEV —
 * the least that holds muscle), the productive range [productiveLow]–
 * [productiveHigh] (MAV), and [maxRecoverableVolume] (MRV).
 */
data class VolumeLandmark(
    val maintenanceVolume: Int,
    val productiveLow: Int,
    val productiveHigh: Int,
    val maxRecoverableVolume: Int,
)

/** One muscle group's counted week. */
data class MuscleGroupVolume(
    val muscleGroup: MuscleGroup,
    /** The Monday the week starts on (ISO week). */
    val weekStart: LocalDate,
    /** Completed sets where this group is the exercise's primary muscle. */
    val primarySets: Int,
    /** Completed sets where this group is a secondary muscle of the exercise. */
    val secondarySets: Int,
    /** [primarySets] + 0.5 × [secondarySets] — the number judged against the landmarks. */
    val effectiveSets: Double,
    val landmark: VolumeLandmark,
    val band: VolumeBand,
)

/**
 * Weekly volume landmarks (blueprint 3.15: "progression and deload decisions
 * from volume-landmark concepts (MEV/MAV/MRV)").
 *
 * Counting: completed sets from completed sessions whose local date falls in
 * the ISO week (Monday–Sunday — the week's definition throughout Forge's
 * weekly rules). A set counts 1.0 toward its exercise's primary muscle group
 * and 0.5 toward each of its secondary groups (indirect work is real but
 * partial stimulus; half-credit is the common convention in the landmark
 * literature). Sets for exercises missing from [library] are skipped — their
 * muscles can't be attributed. FULL_BODY has no landmark row and is not
 * reported (no library exercise uses it; a future full-body movement needs a
 * product decision on attribution, not a silent split).
 *
 * **Honesty note on the numbers:** blueprint 3.15 names the MEV/MAV/MRV
 * concepts but neither it nor the mined evidence base publishes per-muscle
 * values. The table below is the widely-published Renaissance Periodization
 * landmark set (Israetel and colleagues' hypertrophy volume landmarks, the
 * source the MEV/MAV/MRV vocabulary comes from), entered as *defaults pending
 * Kumar's review* — they are starting points for a new trainee's first
 * mesocycle in that system, not findings from Kumar's papers, and every value
 * is a plain constant he can tune.
 */
object VolumeLandmarks {

    /** Default landmark table (see the class KDoc for provenance and status). */
    val LANDMARKS: Map<MuscleGroup, VolumeLandmark> = mapOf(
        MuscleGroup.CHEST to VolumeLandmark(8, 12, 20, 22),
        MuscleGroup.BACK to VolumeLandmark(10, 14, 22, 25),
        MuscleGroup.SHOULDERS to VolumeLandmark(8, 16, 22, 26),
        MuscleGroup.BICEPS to VolumeLandmark(8, 14, 20, 26),
        MuscleGroup.TRICEPS to VolumeLandmark(6, 10, 14, 18),
        MuscleGroup.QUADS to VolumeLandmark(8, 12, 18, 20),
        MuscleGroup.HAMSTRINGS to VolumeLandmark(6, 10, 16, 20),
        MuscleGroup.GLUTES to VolumeLandmark(4, 8, 16, 18),
        MuscleGroup.CALVES to VolumeLandmark(8, 12, 16, 20),
        MuscleGroup.CORE to VolumeLandmark(6, 16, 20, 25),
    )

    /** The Monday of the ISO week containing [date]. */
    fun weekStartOf(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** Classify an effective set count against a landmark row. */
    fun bandFor(effectiveSets: Double, landmark: VolumeLandmark): VolumeBand = when {
        effectiveSets < landmark.maintenanceVolume -> VolumeBand.BELOW_MAINTENANCE
        effectiveSets < landmark.productiveLow -> VolumeBand.MAINTENANCE
        effectiveSets <= landmark.productiveHigh -> VolumeBand.PRODUCTIVE
        effectiveSets <= landmark.maxRecoverableVolume -> VolumeBand.HIGH
        else -> VolumeBand.ABOVE_LANDMARKS
    }

    /**
     * Count the week containing [anyDateInWeek], per muscle group, in
     * landmark-table order. Groups with no completed work report zero counts
     * and [VolumeBand.BELOW_MAINTENANCE] — a rested group and an untrained
     * one look the same in a single week, and the coach reads the trend, not
     * one week, before calling either a problem.
     */
    fun weeklyVolume(
        history: List<CompletedSession>,
        anyDateInWeek: LocalDate,
        zone: ZoneId,
        library: List<Exercise> = SampleData.exercises,
    ): List<MuscleGroupVolume> {
        val weekStart = weekStartOf(anyDateInWeek)
        val weekEnd = weekStart.plusDays(6)
        val byId = library.associateBy { it.id }
        val primary = mutableMapOf<MuscleGroup, Int>()
        val secondary = mutableMapOf<MuscleGroup, Int>()
        for (completed in history) {
            if (!completed.session.isCompleted) continue
            val date = completed.session.startTime.atZone(zone).toLocalDate()
            if (date < weekStart || date > weekEnd) continue
            for (set in completed.sets) {
                if (!set.completed) continue
                val exercise = byId[set.exerciseId] ?: continue
                primary.merge(exercise.muscleGroup, 1, Int::plus)
                for (group in exercise.secondaryMuscleGroups) {
                    secondary.merge(group, 1, Int::plus)
                }
            }
        }
        return LANDMARKS.map { (group, landmark) ->
            val p = primary[group] ?: 0
            val s = secondary[group] ?: 0
            val effective = p + 0.5 * s
            MuscleGroupVolume(group, weekStart, p, s, effective, landmark, bandFor(effective, landmark))
        }
    }
}
