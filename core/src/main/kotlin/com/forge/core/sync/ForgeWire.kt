package com.forge.core.sync

import com.forge.core.model.Equipment
import com.forge.core.model.Exercise
import com.forge.core.model.MuscleGroup
import com.forge.core.model.PRRecord
import com.forge.core.model.PrType
import com.forge.core.model.RoutineExercise
import com.forge.core.model.SetEntry
import com.forge.core.model.WorkoutSession
import com.forge.core.training.CompletedSession
import com.forge.core.training.DroppedExercise
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate

/**
 * Bytes that aren't a payload this version can read. [fromNewerVersion] is
 * true when they came from a newer app; updating this one will read them.
 */
class WireFormatException(
    message: String,
    cause: Throwable? = null,
    val fromNewerVersion: Boolean = false,
) : Exception(message, cause)

/**
 * The phone ↔ watch wire format (blueprint 3.6), shared by both apps so there
 * is one codec and one set of tests.
 *
 * - Plans go phone → watch at [PLANS_PATH].
 * - A finished session goes watch → phone at [sessionPath]. The phone deletes
 *   the item once the session is stored, which is the watch's receipt.
 * - The watch's [SessionJournal] uses the same format on its own disk.
 *
 * JSON with a version number. Unknown fields are ignored, so an older app
 * reads a newer payload that only added fields. A payload whose version is
 * newer than [VERSION] is refused rather than misread: the receiver keeps it
 * until it's updated. Instants and dates are ISO-8601 strings, which are
 * lossless and readable when debugging.
 */
object ForgeWire {

    const val VERSION = 1
    const val PLANS_PATH = "/forge/plans"
    const val SESSION_PATH_PREFIX = "/forge/session/"

    fun sessionPath(sessionId: String): String = SESSION_PATH_PREFIX + sessionId

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encodePlans(plans: WatchPlans): ByteArray = encode(PlansDto.serializer(), plans.toDto())

    fun decodePlans(bytes: ByteArray): WatchPlans =
        decode(bytes, PlansDto.serializer()) { checkVersion(it.v); it.toModel() }

    fun encodeSession(session: WorkoutSession, sets: List<SetEntry>): ByteArray =
        encode(FinishedDto.serializer(), FinishedDto(VERSION, session.toDto(), sets.map { it.toDto() }))

    fun decodeSession(bytes: ByteArray): CompletedSession =
        decode(bytes, FinishedDto.serializer()) {
            checkVersion(it.v)
            CompletedSession(it.session.toModel(), it.sets.map { set -> set.toModel() })
        }

    fun encodeJournal(journal: SessionJournal): ByteArray = encode(JournalDto.serializer(), journal.toDto())

    fun decodeJournal(bytes: ByteArray): SessionJournal =
        decode(bytes, JournalDto.serializer()) { checkVersion(it.v); it.toModel() }

    private fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray =
        json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)

    private fun <T, R> decode(
        bytes: ByteArray,
        serializer: KSerializer<T>,
        map: (T) -> R,
    ): R = try {
        map(json.decodeFromString(serializer, bytes.toString(Charsets.UTF_8)))
    } catch (e: WireFormatException) {
        throw e
    } catch (e: Exception) {
        // Malformed JSON, a missing field, an unknown enum value, a bad date.
        throw WireFormatException("Unreadable Forge payload: ${e.message}", e)
    }

    private fun checkVersion(v: Int) {
        if (v > VERSION) {
            throw WireFormatException("Payload version $v is newer than this app's $VERSION", fromNewerVersion = true)
        }
    }
}

// -- Wire shapes ----------------------------------------------------------------
// Kept separate from the domain models so the models stay free of serialization
// concerns and the format only changes on purpose.

@Serializable
private data class ExerciseDto(
    val id: String,
    val name: String,
    val muscleGroup: MuscleGroup,
    val equipment: Equipment,
    val isCustom: Boolean = false,
    val secondaryMuscleGroups: Set<MuscleGroup> = emptySet(),
)

@Serializable
private data class PrescriptionDto(
    val exercise: ExerciseDto,
    val targetSets: Int,
    val repMin: Int,
    val repMax: Int,
    val targetWeightKg: Double,
    val restSeconds: Int,
)

@Serializable
private data class SetDto(
    val id: String,
    val sessionId: String,
    val exerciseId: String,
    val exerciseName: String,
    val setNumber: Int,
    val weightKg: Double,
    val reps: Int,
    val completed: Boolean = true,
    val timestamp: String,
    val rpe: Double? = null,
    val rir: Int? = null,
    val painReported: Boolean = false,
)

@Serializable
private data class SessionDto(
    val id: String,
    val routineId: String? = null,
    val routineName: String? = null,
    val startTime: String,
    val endTime: String? = null,
    val notes: String? = null,
    val avgHeartRateBpm: Int? = null,
    val maxHeartRateBpm: Int? = null,
    val heartRateRecoveryBpm: Int? = null,
)

@Serializable
private data class PrDto(
    val exerciseId: String,
    val exerciseName: String,
    val type: PrType,
    val value: Double,
    val date: String,
    val sourceSet: SetDto? = null,
)

@Serializable
private data class DroppedDto(val exercise: ExerciseDto, val reason: String)

@Serializable
private data class PlannedDto(
    val prescription: PrescriptionDto,
    val weightReason: String,
    val lastTopSet: SetDto? = null,
    val substitutedFor: ExerciseDto? = null,
    val adjustmentReason: String? = null,
)

@Serializable
private data class PlanDto(
    val date: String,
    val routineId: String,
    val routineName: String,
    val exercises: List<PlannedDto>,
    val droppedExercises: List<DroppedDto> = emptyList(),
)

@Serializable
private data class MenuDto(val exercise: ExerciseDto, val lastTopSet: SetDto? = null)

@Serializable
private data class PlansDto(
    val v: Int,
    val generatedAt: String,
    val firstDay: String,
    val dayCount: Int,
    val days: List<PlanDto>,
    val menu: List<MenuDto>,
    val existingPrs: List<PrDto> = emptyList(),
    val blockedReason: String? = null,
)

@Serializable
private data class FinishedDto(val v: Int, val session: SessionDto, val sets: List<SetDto>)

@Serializable
private sealed interface CommandDto {
    @Serializable
    @SerialName("log")
    data class Log(val weightKg: Double, val reps: Int, val at: String, val painReported: Boolean = false) : CommandDto

    @Serializable
    @SerialName("undo")
    data object Undo : CommandDto

    @Serializable
    @SerialName("skip")
    data object Skip : CommandDto

    @Serializable
    @SerialName("add")
    data class Add(val exerciseId: String) : CommandDto
}

@Serializable
private data class JournalDto(
    val v: Int,
    val sessionId: String,
    val startTime: String,
    val plan: PlanDto? = null,
    val menu: List<MenuDto> = emptyList(),
    val existingPrs: List<PrDto> = emptyList(),
    val commands: List<CommandDto> = emptyList(),
)

// -- Mapping ----------------------------------------------------------------------

private fun Exercise.toDto() = ExerciseDto(id, name, muscleGroup, equipment, isCustom, secondaryMuscleGroups)

private fun ExerciseDto.toModel() = Exercise(
    id = id,
    name = name,
    muscleGroup = muscleGroup,
    equipment = equipment,
    isCustom = isCustom,
    secondaryMuscleGroups = secondaryMuscleGroups,
)

private fun RoutineExercise.toDto() =
    PrescriptionDto(exercise.toDto(), targetSets, repRange.first, repRange.last, targetWeightKg, restSeconds)

private fun PrescriptionDto.toModel() =
    RoutineExercise(exercise.toModel(), targetSets, repMin..repMax, targetWeightKg, restSeconds)

private fun SetEntry.toDto() = SetDto(
    id, sessionId, exerciseId, exerciseName, setNumber, weightKg, reps, completed, timestamp.toString(),
    rpe, rir, painReported,
)

private fun SetDto.toModel() = SetEntry(
    id = id,
    sessionId = sessionId,
    exerciseId = exerciseId,
    exerciseName = exerciseName,
    setNumber = setNumber,
    weightKg = weightKg,
    reps = reps,
    completed = completed,
    timestamp = Instant.parse(timestamp),
    rpe = rpe,
    rir = rir,
    painReported = painReported,
)

private fun WorkoutSession.toDto() = SessionDto(
    id, routineId, routineName, startTime.toString(), endTime?.toString(), notes,
    avgHeartRateBpm, maxHeartRateBpm, heartRateRecoveryBpm,
)

private fun SessionDto.toModel() = WorkoutSession(
    id = id,
    routineId = routineId,
    routineName = routineName,
    startTime = Instant.parse(startTime),
    endTime = endTime?.let(Instant::parse),
    notes = notes,
    avgHeartRateBpm = avgHeartRateBpm,
    maxHeartRateBpm = maxHeartRateBpm,
    heartRateRecoveryBpm = heartRateRecoveryBpm,
)

private fun PRRecord.toDto() = PrDto(exerciseId, exerciseName, type, value, date.toString(), sourceSet?.toDto())

private fun PrDto.toModel() =
    PRRecord(exerciseId, exerciseName, type, value, LocalDate.parse(date), sourceSet?.toModel())

private fun DroppedExercise.toDto() = DroppedDto(exercise.toDto(), reason)

private fun DroppedDto.toModel() = DroppedExercise(exercise.toModel(), reason)

private fun PlannedExercise.toDto() = PlannedDto(
    prescription.toDto(), weightReason, lastTopSet?.toDto(), substitutedFor?.toDto(), adjustmentReason,
)

private fun PlannedDto.toModel() = PlannedExercise(
    prescription = prescription.toModel(),
    weightReason = weightReason,
    lastTopSet = lastTopSet?.toModel(),
    substitutedFor = substitutedFor?.toModel(),
    adjustmentReason = adjustmentReason,
)

private fun WorkoutPlan.toDto() = PlanDto(
    date.toString(), routineId, routineName, exercises.map { it.toDto() }, droppedExercises.map { it.toDto() },
)

private fun PlanDto.toModel() = WorkoutPlan(
    date = LocalDate.parse(date),
    routineId = routineId,
    routineName = routineName,
    exercises = exercises.map { it.toModel() },
    droppedExercises = droppedExercises.map { it.toModel() },
)

private fun MenuExercise.toDto() = MenuDto(exercise.toDto(), lastTopSet?.toDto())

private fun MenuDto.toModel() = MenuExercise(exercise.toModel(), lastTopSet?.toModel())

private fun WatchPlans.toDto() = PlansDto(
    v = ForgeWire.VERSION,
    generatedAt = generatedAt.toString(),
    firstDay = firstDay.toString(),
    dayCount = dayCount,
    days = days.map { it.toDto() },
    menu = menu.map { it.toDto() },
    existingPrs = existingPrs.map { it.toDto() },
    blockedReason = blockedReason,
)

private fun PlansDto.toModel() = WatchPlans(
    generatedAt = Instant.parse(generatedAt),
    firstDay = LocalDate.parse(firstDay),
    dayCount = dayCount,
    days = days.map { it.toModel() },
    menu = menu.map { it.toModel() },
    existingPrs = existingPrs.map { it.toModel() },
    blockedReason = blockedReason,
)

private fun SessionCommand.toDto(): CommandDto = when (this) {
    is SessionCommand.LogSet -> CommandDto.Log(weightKg, reps, at.toString(), painReported)
    SessionCommand.UndoLastSet -> CommandDto.Undo
    SessionCommand.SkipExercise -> CommandDto.Skip
    is SessionCommand.AddExercise -> CommandDto.Add(exerciseId)
}

private fun CommandDto.toModel(): SessionCommand = when (this) {
    is CommandDto.Log -> SessionCommand.LogSet(weightKg, reps, Instant.parse(at), painReported)
    CommandDto.Undo -> SessionCommand.UndoLastSet
    CommandDto.Skip -> SessionCommand.SkipExercise
    is CommandDto.Add -> SessionCommand.AddExercise(exerciseId)
}

private fun SessionJournal.toDto() = JournalDto(
    v = ForgeWire.VERSION,
    sessionId = sessionId,
    startTime = startTime.toString(),
    plan = plan?.toDto(),
    menu = menu.map { it.toDto() },
    existingPrs = existingPrs.map { it.toDto() },
    commands = commands.map { it.toDto() },
)

private fun JournalDto.toModel() = SessionJournal(
    sessionId = sessionId,
    startTime = Instant.parse(startTime),
    plan = plan?.toModel(),
    menu = menu.map { it.toModel() },
    existingPrs = existingPrs.map { it.toModel() },
    commands = commands.map { it.toModel() },
)
