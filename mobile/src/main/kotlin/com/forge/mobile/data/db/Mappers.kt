package com.forge.mobile.data.db

import com.forge.core.bodycomp.BiaConditions
import com.forge.core.bodycomp.BiaReading
import com.forge.core.bodycomp.DexaBrand
import com.forge.core.bodycomp.DexaScan
import com.forge.core.bodycomp.DexaScanner
import com.forge.core.bodycomp.TapeMeasurement
import com.forge.core.model.ActivityLevel
import com.forge.core.model.EquipmentAccess
import com.forge.core.model.ExperienceLevel
import com.forge.core.model.GoalProfile
import com.forge.core.model.GoalType
import com.forge.core.model.SetEntry
import com.forge.core.model.Sex
import com.forge.core.model.WorkoutSession
import com.forge.core.nutrition.WeighIn
import org.json.JSONArray
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// Two-way mapping between :core models and storage records. Instants are
// stored at millisecond precision; anything finer is truncated on write.

fun WorkoutSession.toEntity() = WorkoutSessionEntity(
    id = id,
    routineId = routineId,
    routineName = routineName,
    startTimeEpochMs = startTime.toEpochMilli(),
    endTimeEpochMs = endTime?.toEpochMilli(),
    notes = notes,
    avgHeartRateBpm = avgHeartRateBpm,
    maxHeartRateBpm = maxHeartRateBpm,
    heartRateRecoveryBpm = heartRateRecoveryBpm,
)

fun WorkoutSessionEntity.toModel() = WorkoutSession(
    id = id,
    routineId = routineId,
    routineName = routineName,
    startTime = Instant.ofEpochMilli(startTimeEpochMs),
    endTime = endTimeEpochMs?.let(Instant::ofEpochMilli),
    notes = notes,
    avgHeartRateBpm = avgHeartRateBpm,
    maxHeartRateBpm = maxHeartRateBpm,
    heartRateRecoveryBpm = heartRateRecoveryBpm,
)

fun SetEntry.toEntity() = SetEntryEntity(
    id = id,
    sessionId = sessionId,
    exerciseId = exerciseId,
    exerciseName = exerciseName,
    setNumber = setNumber,
    weightKg = weightKg,
    reps = reps,
    completed = completed,
    timestampEpochMs = timestamp.toEpochMilli(),
    rpe = rpe,
    rir = rir,
    painReported = painReported,
)

fun SetEntryEntity.toModel() = SetEntry(
    id = id,
    sessionId = sessionId,
    exerciseId = exerciseId,
    exerciseName = exerciseName,
    setNumber = setNumber,
    weightKg = weightKg,
    reps = reps,
    completed = completed,
    timestamp = Instant.ofEpochMilli(timestampEpochMs),
    rpe = rpe,
    rir = rir,
    painReported = painReported,
)

fun GoalProfile.toEntity() = GoalProfileEntity(
    goal = goal.name,
    ageYears = ageYears,
    sex = sex.name,
    heightCm = heightCm,
    weightKg = weightKg,
    trainingDaysPerWeek = trainingDaysPerWeek,
    experienceLevel = experienceLevel.name,
    equipmentAccess = equipmentAccess.name,
    activityLevel = activityLevel.name,
    calorieTargetKcal = calorieTargetKcal,
    proteinTargetGrams = proteinTargetGrams,
    carbTargetGrams = carbTargetGrams,
    fatTargetGrams = fatTargetGrams,
    dietaryPattern = dietaryPattern,
    dislikesJson = dislikes.toJsonArray(),
    allergiesJson = allergies.toJsonArray(),
)

fun GoalProfileEntity.toModel() = GoalProfile(
    goal = GoalType.valueOf(goal),
    ageYears = ageYears,
    sex = Sex.valueOf(sex),
    heightCm = heightCm,
    weightKg = weightKg,
    trainingDaysPerWeek = trainingDaysPerWeek,
    experienceLevel = ExperienceLevel.valueOf(experienceLevel),
    equipmentAccess = EquipmentAccess.valueOf(equipmentAccess),
    activityLevel = ActivityLevel.valueOf(activityLevel),
    calorieTargetKcal = calorieTargetKcal,
    proteinTargetGrams = proteinTargetGrams,
    carbTargetGrams = carbTargetGrams,
    fatTargetGrams = fatTargetGrams,
    dietaryPattern = dietaryPattern,
    dislikes = dislikesJson.toStringList(),
    allergies = allergiesJson.toStringList(),
)

fun WeighIn.toEntity() = WeighInEntity(epochDay = date.toEpochDay(), weightKg = weightKg)

fun WeighInEntity.toModel() = WeighIn(date = LocalDate.ofEpochDay(epochDay), weightKg = weightKg)

fun BiaReading.toEntity() = BiaReadingEntity(
    takenAtLocal = takenAt.format(LOCAL_TIME_FORMAT),
    fatPercent = fatPercent,
    fasted = conditions.fasted,
    postVoid = conditions.postVoid,
    usualHydration = conditions.usualHydration,
)

fun BiaReadingEntity.toModel() = BiaReading(
    takenAt = LocalDateTime.parse(takenAtLocal, LOCAL_TIME_FORMAT),
    fatPercent = fatPercent,
    conditions = BiaConditions(fasted = fasted, postVoid = postVoid, usualHydration = usualHydration),
)

fun TapeMeasurement.toEntity() = TapeMeasurementEntity(
    epochDay = date.toEpochDay(),
    neckCm = neckCm,
    waistAtNavelCm = waistAtNavelCm,
    naturalWaistCm = naturalWaistCm,
    waistAtIliacCrestCm = waistAtIliacCrestCm,
    hipCm = hipCm,
)

fun TapeMeasurementEntity.toModel() = TapeMeasurement(
    date = LocalDate.ofEpochDay(epochDay),
    neckCm = neckCm,
    waistAtNavelCm = waistAtNavelCm,
    naturalWaistCm = naturalWaistCm,
    waistAtIliacCrestCm = waistAtIliacCrestCm,
    hipCm = hipCm,
)

fun DexaScan.toEntity() = DexaScanEntity(
    epochDay = date.toEpochDay(),
    scannerBrand = scanner.brand.name,
    scannerSite = scanner.site,
    fatPercent = fatPercent,
    fatMassKg = fatMassKg,
    leanSoftTissueKg = leanSoftTissueKg,
    boneMineralContentKg = boneMineralContentKg,
)

fun DexaScanEntity.toModel() = DexaScan(
    date = LocalDate.ofEpochDay(epochDay),
    scanner = DexaScanner(DexaBrand.valueOf(scannerBrand), scannerSite),
    fatPercent = fatPercent,
    fatMassKg = fatMassKg,
    leanSoftTissueKg = leanSoftTissueKg,
    boneMineralContentKg = boneMineralContentKg,
)

/** Fixed-width local timestamp, second precision, so text order is time order. */
private val LOCAL_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss")

private fun List<String>.toJsonArray(): String = JSONArray(this).toString()

private fun String.toStringList(): List<String> {
    val array = JSONArray(this)
    return List(array.length()) { array.getString(it) }
}
