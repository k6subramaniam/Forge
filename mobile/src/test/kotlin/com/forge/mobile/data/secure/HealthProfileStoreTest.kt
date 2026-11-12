package com.forge.mobile.data.secure

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.forge.core.model.ClearanceState
import com.forge.core.model.HealthProfile
import com.forge.core.model.JointArea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
class HealthProfileStoreTest {

    private lateinit var file: File
    private val key: SecretKey = newKey()

    private val profile = HealthProfile(
        hasHighBloodPressure = true,
        takesHeartRateAffectingMedication = true,
        jointLimitations = setOf(JointArea.SHOULDER, JointArea.KNEE),
        injuriesOrSurgeries = "Torn ACL repaired 2019",
        freeTextLimitations = "Left shoulder clicks overhead",
        clearanceState = ClearanceState.NOT_REQUIRED,
        onboardingComplete = true,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File(context.noBackupFilesDir, "health_profile_test.enc").also { it.delete() }
    }

    @Test
    fun `a written profile reads back identical`() = runTest {
        val store = HealthProfileStore(file) { key }
        store.write(profile)
        assertEquals(profile, store.read())
    }

    @Test
    fun `no file reads as no profile`() = runTest {
        assertNull(HealthProfileStore(file) { key }.read())
    }

    @Test
    fun `nothing is stored in plaintext`() = runTest {
        HealthProfileStore(file) { key }.write(profile)
        val onDisk = String(file.readBytes(), Charsets.ISO_8859_1)
        listOf("Torn ACL", "shoulder", "onboardingComplete", "SHOULDER", "clearanceState").forEach {
            assertFalse("found '$it' in the file", onDisk.contains(it))
        }
    }

    @Test
    fun `each write uses a fresh IV`() = runTest {
        val store = HealthProfileStore(file) { key }
        store.write(profile)
        val first = file.readBytes()
        store.write(profile)
        assertFalse(first.contentEquals(file.readBytes()))
        assertEquals(profile, store.read())
    }

    @Test
    fun `a different key cannot read it, and that reads as no profile`() = runTest {
        HealthProfileStore(file) { key }.write(profile)
        val other = newKey()
        assertNull(HealthProfileStore(file) { other }.read())
    }

    @Test
    fun `a corrupt file reads as no profile`() = runTest {
        val store = HealthProfileStore(file) { key }
        store.write(profile)
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte() // break the GCM tag
        file.writeBytes(bytes)
        assertNull(store.read())

        file.writeBytes(byteArrayOf(9, 9, 9))
        assertNull(store.read())
    }

    @Test
    fun `clear removes the profile`() = runTest {
        val store = HealthProfileStore(file) { key }
        store.write(profile)
        store.clear()
        assertNull(store.read())
    }

    @Test
    fun `reads running alongside writes never lose a write`() = runBlocking {
        // A read starting mid-write must not disturb it (AtomicFile deletes an
        // in-progress ".new" file when a read opens the base file).
        val store = HealthProfileStore(file) { key }
        store.write(profile)
        val reader = launch(Dispatchers.IO) {
            while (isActive) store.read()
        }
        val versions = (1..200).map { profile.copy(injuriesOrSurgeries = "version $it") }
        for (version in versions) {
            withContext(Dispatchers.IO) { store.write(version) }
            assertEquals(version, store.read())
        }
        reader.cancelAndJoin()
    }

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}
