package com.forge.core.nutrition

import com.forge.core.model.GoalProfile
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** One day's total logged intake. Only complete, honestly logged days should be passed in. */
data class IntakeDay(
    val date: LocalDate,
    val caloriesKcal: Int,
)

/** Which estimate an [AdaptiveTdeeResult] reports as maintenance. */
enum class TdeeBasis {
    /** Enough logged data: maintenance comes from back-calculation (blueprint 3.15). */
    BACK_CALCULATED,

    /** Not enough data yet: maintenance is the formula estimate, plainly labelled. */
    FORMULA,
}

/**
 * The adaptive maintenance estimate (blueprint 3.15). The counts and the
 * back-calculated value ride along even on a formula result, so the UI can
 * say "formula estimate — 6 more logged days unlocks your measured
 * maintenance" instead of silently pretending either way.
 */
data class AdaptiveTdeeResult(
    /** The maintenance to build targets from, kcal/day. */
    val maintenanceKcal: Int,
    val basis: TdeeBasis,
    /** Mifflin-St Jeor × activity, always computed (blueprint 3.15: the prior). */
    val formulaTdeeKcal: Int,
    /** The back-calculated maintenance, or null when data was insufficient. */
    val backCalculatedTdeeKcal: Int?,
    /**
     * Mean logged intake over the logged days inside the weigh-in interval
     * (first to last weigh-in in the window); null when none were logged.
     */
    val averageIntakeKcal: Double?,
    /** Trend-weight change across the weigh-in span, kg (null: no rate knowable). */
    val trendChangeKg: Double?,
    val windowStart: LocalDate,
    val windowEnd: LocalDate,
    val weighInSpanDays: Long,
    val intakeDaysUsed: Int,
    val weighInsUsed: Int,
)

/** Why a [WeeklyAdjustment] says what it says. */
enum class AdjustmentReason {
    /** Trend rate matches the goal's target rate within tolerance. */
    ON_TRACK,

    /** Trend rate is off target; the suggested step moves intake against the error. */
    OFF_TARGET,

    /** Trend too short or too sparse to judge — hold rather than guess. */
    INSUFFICIENT_DATA,

    /** A decrease was needed but the current target already sits at the BMR floor. */
    AT_FLOOR,
}

enum class AdjustmentDirection {
    INCREASE,
    DECREASE,
    HOLD,
}

/** The weekly calorie adjustment decision (blueprint 3.8). Suggestion only — the user confirms. */
data class WeeklyAdjustment(
    val currentTargetKcal: Int,
    val suggestedTargetKcal: Int,
    /** [suggestedTargetKcal] minus [currentTargetKcal] (0 on a hold). */
    val deltaKcal: Int,
    val direction: AdjustmentDirection,
    val reason: AdjustmentReason,
    /** True when the BMR floor limited (or blocked) a decrease. */
    val floorApplied: Boolean,
    /** The measured trend rate behind the decision; null when unknowable. */
    val trendRateKgPerWeek: Double?,
    /** The goal's intended rate the trend was judged against. */
    val targetRateKgPerWeek: Double,
    /** Measured minus intended rate (null when the rate is unknowable). */
    val deviationKgPerWeek: Double?,
)

/**
 * Adaptive TDEE and the weekly adjustment rule (blueprint 3.15 "Energy and
 * nutrition algorithms", 3.8 "AI diet coaching").
 *
 * Maintenance calories are back-calculated from the energy-balance identity:
 * over a window, average intake minus the energy stored (or released) as
 * body mass equals expenditure — TDEE ≈ mean intake − Δtrend·kg × 7,700
 * kcal/kg ÷ days. Formula TDEE (Mifflin-St Jeor × activity) is the prior for
 * new users and the fallback whenever the logged data can't carry the
 * calculation; the result says which basis it used rather than blending two
 * unlike numbers into false confidence.
 */
object AdaptiveTdee {

    /** Blueprint 3.15: back-calculation reads "2–3 weeks" of data; the window defaults to 3. */
    const val DEFAULT_WINDOW_DAYS = 21

    /** Minimum weigh-in span for a back-calculation: the short end of 3.15's 2–3 weeks. */
    const val MIN_WEIGH_IN_SPAN_DAYS = 14L

    /** Minimum weigh-ins inside the window for the trend to be trustworthy. */
    const val MIN_WEIGH_INS = 4

    /** Minimum logged intake days inside the window for the mean to be trustworthy. */
    const val MIN_INTAKE_DAYS = 10

    /**
     * Estimate maintenance for [profile] from weigh-ins and intake logged in
     * the [windowDays] ending at [endDate] (dates are parameters — core stays
     * clock-free, the HistoryReadModels convention).
     *
     * Sufficiency bar, all four required: at least [MIN_WEIGH_INS] weigh-ins
     * spanning at least [MIN_WEIGH_IN_SPAN_DAYS], at least [MIN_INTAKE_DAYS]
     * logged intake days, and a computable trend rate. The trend change is
     * the regression rate × the weigh-in span (not the raw first-to-last
     * scale difference), so one heavy Monday can't fake a surplus.
     *
     * Intake is averaged over the same interval the weight change was measured
     * on: the window's first weigh-in to its last, not the whole window. A diet
     * change before the first weigh-in belongs to a different energy balance
     * and must not bias the measured maintenance. The intake-day minimum is
     * checked on that interval too.
     *
     * The back-calculated value is clamped to [BMR, 2 × formula TDEE] as a
     * corruption guard — a mis-logged feast day or a scale error shouldn't be
     * able to drag maintenance somewhere physiology can't be.
     */
    fun estimate(
        profile: GoalProfile,
        weighIns: List<WeighIn>,
        intakeDays: List<IntakeDay>,
        endDate: LocalDate,
        windowDays: Int = DEFAULT_WINDOW_DAYS,
    ): AdaptiveTdeeResult {
        require(windowDays > 0) { "windowDays must be positive" }
        val windowStart = endDate.minusDays((windowDays - 1).toLong())
        val formula = EnergyTargets.formulaTdee(profile)

        val windowWeighIns = weighIns.filter { it.date in windowStart..endDate }
        val trend = BodyweightTrend.analyse(windowWeighIns)
        // The interval the weight change was observed over. With fewer than two
        // weigh-ins there is no interval (and no back-calculation); the window's
        // intake is still reported for display.
        val weighInDates = windowWeighIns.map { it.date }
        val intakeRange = if (weighInDates.distinct().size >= 2) {
            weighInDates.min()..weighInDates.max()
        } else {
            windowStart..endDate
        }
        val windowIntake = intakeDays.filter { it.date in windowStart..endDate && it.date in intakeRange }
        val averageIntake = if (windowIntake.isEmpty()) {
            null
        } else {
            windowIntake.sumOf { it.caloriesKcal }.toDouble() / windowIntake.size
        }

        val rate = trend.weeklyRateKgPerWeek
        val trendChangeKg = if (rate != null && trend.spanDays > 0) {
            rate / 7.0 * trend.spanDays
        } else {
            null
        }
        val sufficient = windowWeighIns.size >= MIN_WEIGH_INS &&
            trend.spanDays >= MIN_WEIGH_IN_SPAN_DAYS &&
            windowIntake.size >= MIN_INTAKE_DAYS &&
            trendChangeKg != null &&
            averageIntake != null

        val backCalculated = if (sufficient) {
            val dailyStoredKcal = checkNotNull(trendChangeKg) *
                EnergyTargets.KCAL_PER_KG_BODY_MASS / trend.spanDays
            val raw = checkNotNull(averageIntake) - dailyStoredKcal
            raw.coerceIn(EnergyTargets.bmr(profile), 2.0 * formula).roundToInt()
        } else {
            null
        }

        return AdaptiveTdeeResult(
            maintenanceKcal = backCalculated ?: formula.roundToInt(),
            basis = if (backCalculated != null) TdeeBasis.BACK_CALCULATED else TdeeBasis.FORMULA,
            formulaTdeeKcal = formula.roundToInt(),
            backCalculatedTdeeKcal = backCalculated,
            averageIntakeKcal = averageIntake,
            trendChangeKg = trendChangeKg,
            windowStart = windowStart,
            windowEnd = endDate,
            weighInSpanDays = trend.spanDays,
            intakeDaysUsed = windowIntake.size,
            weighInsUsed = windowWeighIns.size,
        )
    }

    /** A trend needs at least this many days of span before the weekly rule will judge it. */
    const val MIN_TREND_SPAN_DAYS = 7L

    /** Trend rate within this of the target rate (kg/week) counts as on track. */
    const val ON_TRACK_TOLERANCE_KG_PER_WEEK = 0.1

    /** Deviations up to this (kg/week) take the small step; larger ones the large step. */
    const val SMALL_DEVIATION_KG_PER_WEEK = 0.25

    /** Blueprint 3.8 bounds weekly adjustments to ±100–200 kcal — the small step. */
    const val STEP_SMALL_KCAL = 100

    /** Blueprint 3.8 bounds weekly adjustments to ±100–200 kcal — the large step. */
    const val STEP_LARGE_KCAL = 200

    /**
     * The weekly adjustment (blueprint 3.8: "weekly calorie adjustment from
     * the bodyweight trend — gaining too fast / too slow → ±100–200 kcal").
     *
     * The measured trend rate is compared with the goal's intended rate
     * ([EnergyTargets.targetRateKgPerWeek], judged at the trend weight).
     * Within [ON_TRACK_TOLERANCE_KG_PER_WEEK] the target holds. Outside it,
     * the step pushes intake against the error — losing too slowly (or
     * gaining too fast) decreases the target, the reverse increases it — by
     * 100 kcal for a small miss, 200 kcal for a large one, never more:
     * one bounded lever per week, per the evidence base's one-lever
     * discipline. A decrease can never cross the BMR floor; a target already
     * at the floor holds and says so ([AdjustmentReason.AT_FLOOR]).
     */
    fun weeklyAdjustment(
        profile: GoalProfile,
        currentTargetKcal: Int,
        trend: WeightTrend,
    ): WeeklyAdjustment {
        val targetRate = EnergyTargets.targetRateKgPerWeek(
            profile.goal,
            trend.latestTrendKg ?: profile.weightKg,
        )
        val actualRate = trend.weeklyRateKgPerWeek
        if (actualRate == null || trend.spanDays < MIN_TREND_SPAN_DAYS) {
            return WeeklyAdjustment(
                currentTargetKcal = currentTargetKcal,
                suggestedTargetKcal = currentTargetKcal,
                deltaKcal = 0,
                direction = AdjustmentDirection.HOLD,
                reason = AdjustmentReason.INSUFFICIENT_DATA,
                floorApplied = false,
                trendRateKgPerWeek = actualRate,
                targetRateKgPerWeek = targetRate,
                deviationKgPerWeek = null,
            )
        }
        val deviation = actualRate - targetRate
        if (abs(deviation) <= ON_TRACK_TOLERANCE_KG_PER_WEEK) {
            return WeeklyAdjustment(
                currentTargetKcal = currentTargetKcal,
                suggestedTargetKcal = currentTargetKcal,
                deltaKcal = 0,
                direction = AdjustmentDirection.HOLD,
                reason = AdjustmentReason.ON_TRACK,
                floorApplied = false,
                trendRateKgPerWeek = actualRate,
                targetRateKgPerWeek = targetRate,
                deviationKgPerWeek = deviation,
            )
        }
        val step = if (abs(deviation) <= SMALL_DEVIATION_KG_PER_WEEK) STEP_SMALL_KCAL else STEP_LARGE_KCAL
        return if (deviation > 0.0) {
            // Rate above target: gaining too fast, or losing too slowly — eat less.
            val floor = ceil(EnergyTargets.bmr(profile)).toInt()
            val suggested = maxOf(floor, currentTargetKcal - step)
            val delta = suggested - currentTargetKcal
            WeeklyAdjustment(
                currentTargetKcal = currentTargetKcal,
                suggestedTargetKcal = suggested,
                deltaKcal = delta,
                direction = if (delta < 0) AdjustmentDirection.DECREASE else AdjustmentDirection.HOLD,
                reason = if (delta == 0) AdjustmentReason.AT_FLOOR else AdjustmentReason.OFF_TARGET,
                floorApplied = delta > -step,
                trendRateKgPerWeek = actualRate,
                targetRateKgPerWeek = targetRate,
                deviationKgPerWeek = deviation,
            )
        } else {
            // Rate below target: losing too fast, or gaining too slowly — eat more.
            WeeklyAdjustment(
                currentTargetKcal = currentTargetKcal,
                suggestedTargetKcal = currentTargetKcal + step,
                deltaKcal = step,
                direction = AdjustmentDirection.INCREASE,
                reason = AdjustmentReason.OFF_TARGET,
                floorApplied = false,
                trendRateKgPerWeek = actualRate,
                targetRateKgPerWeek = targetRate,
                deviationKgPerWeek = deviation,
            )
        }
    }
}
