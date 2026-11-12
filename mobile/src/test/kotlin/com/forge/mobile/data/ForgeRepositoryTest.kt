package com.forge.mobile.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.forge.core.bodycomp.BiaConditions
import com.forge.core.bodycomp.BiaProtocol
import com.forge.core.bodycomp.BiaReading
import com.forge.core.bodycomp.DexaBrand
import com.forge.core.bodycomp.DexaScan
import com.forge.core.bodycomp.DexaScanner
import com.forge.core.bodycomp.TapeMeasurement
import com.forge.core.data.ExerciseLibrary
import com.forge.core.model.ActivityLevel
import com.forge.core.model.ClearanceState
import com.forge.core.model.EquipmentAccess
import com.forge.core.model.ExperienceLevel
import com.forge.core.model.GoalProfile
import com.forge.core.model.GoalType
import com.forge.core.model.HealthProfile
import com.forge.core.model.JointArea
import com.forge.core.model.SetEntry
import com.forge.core.model.Sex
import com.forge.core.model.WorkoutSession
import com.forge.core.nutrition.WeighIn
import com.forge.core.sync.WatchPlanner
import com.forge.mobile.data.db.ForgeDatabase
import com.forge.mobile.data.secure.HealthProfileStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
class ForgeRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val healthFile get() = File(context.noBackupFilesDir, "health_profile_repo_test.enc")
    private val opened = mutableListOf<ForgeDatabase>()

    private val t0: Instant = Instant.parse("2026-11-01T07:00:00Z")

    private fun openRepository(): ForgeRepository {
        val database = Room.databaseBuilder(context, ForgeDatabase::class.java, DB_NAME).build()
        opened += database
        return ForgeRepository(database, HealthProfileStore(healthFile) { key })
    }

    @Before
    fun setUp() {
        context.deleteDatabase(DB_NAME)
        healthFile.delete()
    }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        context.deleteDatabase(DB_NAME)
        healthFile.delete()
    }

    private fun session(id: String, start: Instant, end: Instant? = start.plusSeconds(3_000)) =
        WorkoutSession(id = id, routineId = "routine-push-a", routineName = "Push A", startTime = start, endTime = end)

    private fun set(
        id: String,
        sessionId: String,
        setNumber: Int,
        at: Instant,
        exerciseId: String = "ex-bench-press",
        completed: Boolean = true,
        weight: Double = 60.0,
    ) = SetEntry(id, sessionId, exerciseId, "Bench Press", setNumber, weight, 8, completed, at)

    @Test
    fun `onboarding survives a restart`() = runTest {
        // The point of this slice: answers given once are still there next launch.
        val goal = GoalProfile(
            goal = GoalType.RECOMP,
            ageYears = 38,
            sex = Sex.MALE,
            heightCm = 178.0,
            weightKg = 84.5,
            trainingDaysPerWeek = 4,
            experienceLevel = ExperienceLevel.INTERMEDIATE,
            equipmentAccess = EquipmentAccess.FULL_GYM,
            activityLevel = ActivityLevel.MODERATE,
            dietaryPattern = "vegetarian",
            dislikes = listOf("olives", "blue cheese, aged"),
            allergies = listOf("peanuts", "shellfish \"all\""),
        )
        val health = HealthProfile(
            hasExertionalChestPain = true,
            jointLimitations = setOf(JointArea.LOWER_BACK),
            clearanceState = ClearanceState.REQUIRED,
            onboardingComplete = true,
        )
        openRepository().saveOnboarding(goal, health)
        opened.forEach { it.close() }

        val relaunched = openRepository()
        assertEquals(goal, relaunched.goalProfile())
        assertEquals(health, relaunched.healthProfile())
    }

    @Test
    fun `nothing saved yet means no profiles`() = runTest {
        val repository = openRepository()
        assertNull(repository.goalProfile())
        assertNull(repository.healthProfile())
    }

    @Test
    fun `history lists sessions newest first, each with its sets in order`() = runTest {
        val repository = openRepository()
        val older = session("s-old", t0)
        val newer = session("s-new", t0.plusSeconds(86_400))
        repository.addSession(older, listOf(set("o1", "s-old", 1, t0)))
        repository.addSession(
            newer,
            listOf(
                set("n3", "s-new", 3, newer.startTime.plusSeconds(600)),
                set("n1", "s-new", 1, newer.startTime.plusSeconds(60)),
                set("n2", "s-new", 2, newer.startTime.plusSeconds(300)),
            ),
        )

        val history = repository.observeHistory().first()
        assertEquals(listOf("s-new", "s-old"), history.map { it.session.id })
        assertEquals(listOf(1, 2, 3), history.first().sets.map { it.setNumber })
        assertEquals(newer, history.first().session)
    }

    @Test
    fun `re-adding a session updates it without dropping its sets`() = runTest {
        // An update must not delete-and-reinsert the session row: the foreign-key
        // cascade would silently take every set with it.
        val repository = openRepository()
        val s = session("s1", t0)
        repository.addSession(s, (1..3).map { set("x$it", "s1", it, t0.plusSeconds(it * 60L)) })

        val finished = s.copy(endTime = t0.plusSeconds(4_000), notes = "Felt strong")
        repository.addSession(finished, emptyList())

        val stored = repository.observeHistory().first().single()
        assertEquals(finished, stored.session)
        assertEquals(3, stored.sets.size)
    }

    @Test
    fun `a set must belong to the session it is saved with`() = runTest {
        val repository = openRepository()
        val error = runCatching {
            repository.addSession(session("s1", t0), listOf(set("x", "other-session", 1, t0)))
        }.exceptionOrNull()
        assertTrue("expected IllegalArgumentException, got $error", error is IllegalArgumentException)
        assertTrue(repository.observeHistory().first().isEmpty())
    }

    @Test
    fun `every set field round-trips, including RPE, RIR and pain`() = runTest {
        val repository = openRepository()
        val detailed = set("d1", "s1", 1, t0.plusMillis(1_234)).copy(rpe = 8.5, rir = 2, painReported = true)
        repository.addSession(session("s1", t0), listOf(detailed))
        assertEquals(listOf(detailed), repository.setsFor("s1"))
    }

    @Test
    fun `last performance is the latest completed set of that exercise`() = runTest {
        val repository = openRepository()
        repository.addSession(session("a", t0), listOf(set("a1", "a", 1, t0, weight = 60.0)))
        repository.addSession(
            session("b", t0.plusSeconds(86_400)),
            listOf(
                set("b1", "b", 1, t0.plusSeconds(86_460), weight = 62.5),
                // Newer, but not completed: never a ghost target.
                set("b2", "b", 2, t0.plusSeconds(86_520), completed = false, weight = 65.0),
                // Newest of all, but a different exercise.
                set("b3", "b", 3, t0.plusSeconds(86_580), exerciseId = "ex-squat", weight = 100.0),
            ),
        )
        assertEquals("b1", repository.lastPerformance("ex-bench-press")?.id)
        assertNull(repository.lastPerformance("ex-deadlift"))
    }

    @Test
    fun `history re-emits when a session is added`() = runTest {
        val repository = openRepository()
        assertTrue(repository.observeHistory().first().isEmpty())
        repository.addSession(session("s1", t0), emptyList())
        assertEquals(1, repository.observeHistory().first().size)
    }

    @Test
    fun `one weigh-in per day, the latest replacing the first`() = runTest {
        val repository = openRepository()
        val day = LocalDate.of(2026, 11, 3)
        repository.logWeighIn(WeighIn(day.plusDays(1), 84.1))
        repository.logWeighIn(WeighIn(day, 84.6))
        repository.logWeighIn(WeighIn(day, 84.4))
        assertEquals(
            listOf(WeighIn(day, 84.4), WeighIn(day.plusDays(1), 84.1)),
            repository.observeWeighIns().first(),
        )
    }

    @Test
    fun `body-composition inputs round-trip and BIA QC runs on what was stored`() = runTest {
        val repository = openRepository()
        val day = LocalDate.of(2026, 11, 3)
        val good = BiaConditions(fasted = true, postVoid = true, usualHydration = true)
        val morning = BiaReading(LocalDateTime.of(day, java.time.LocalTime.of(6, 45, 10)), 21.4, good)
        val evening = BiaReading(LocalDateTime.of(day, java.time.LocalTime.of(20, 15)), 23.0, good)
        repository.logBiaReading(evening)
        repository.logBiaReading(morning)
        val tape = TapeMeasurement(day, neckCm = 38.0, waistAtNavelCm = 90.5, waistAtIliacCrestCm = 91.0)
        repository.logTapeMeasurement(tape)
        val scan = DexaScan(
            day, DexaScanner(DexaBrand.HOLOGIC, "Liberty Village"), 19.2,
            fatMassKg = 16.2, leanSoftTissueKg = 65.0, boneMineralContentKg = 3.1,
        )
        repository.logDexaScan(scan)

        val bia = repository.biaReadings()
        assertEquals(listOf(morning, evening), bia) // ordered by local time
        assertEquals(listOf(21.4), BiaProtocol.acceptedEstimates(bia).map { it.fatPercent })
        assertEquals(listOf(tape), repository.tapeMeasurements())
        assertEquals(listOf(scan), repository.dexaScans())
    }

    @Test
    fun `the watch gets nothing to run until onboarding is done`() = runTest {
        val plans = openRepository().plans(LocalDate.of(2026, 11, 16), java.time.ZoneOffset.UTC, t0)
        assertEquals(WatchPlanner.ONBOARDING_UNFINISHED, plans.blockedReason)
        assertTrue(plans.menu.isEmpty())
    }

    @Test
    fun `plans for the watch are built from stored history and the health profile`() = runTest {
        val repository = openRepository()
        repository.saveOnboarding(
            GoalProfile(
                goal = GoalType.RECOMP, ageYears = 38, sex = Sex.MALE, heightCm = 178.0, weightKg = 84.5,
                trainingDaysPerWeek = 3, experienceLevel = ExperienceLevel.INTERMEDIATE,
                equipmentAccess = EquipmentAccess.FULL_GYM, activityLevel = ActivityLevel.MODERATE,
            ),
            HealthProfile(jointLimitations = setOf(JointArea.SHOULDER), onboardingComplete = true),
        )
        repository.addSession(session("s1", t0), listOf(set("a", "s1", 1, t0, weight = 70.0)))
        // An unfinished session isn't history.
        repository.addSession(session("s2", t0.plusSeconds(86_400), end = null), emptyList())

        assertEquals(listOf("s1"), repository.completedSessions().map { it.session.id })

        val plans = repository.plans(LocalDate.of(2026, 11, 16), java.time.ZoneOffset.UTC, t0)
        assertNull(plans.blockedReason)
        // The shoulder limitation is applied before plans leave the phone.
        assertTrue(plans.menu.none { it.exercise.id == ExerciseLibrary.overheadPress.id })
        assertEquals(70.0, plans.menu.first { it.exercise.id == "ex-bench-press" }.lastTopSet?.weightKg)
        assertTrue(plans.existingPrs.all { it.exerciseId == "ex-bench-press" })
    }

    private companion object {
        const val DB_NAME = "forge-repository-test.db"
    }
}
