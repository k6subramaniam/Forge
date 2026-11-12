package com.forge.mobile.data.secure

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Log
import com.forge.core.model.ClearanceState
import com.forge.core.model.HealthProfile
import com.forge.core.model.JointArea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Supplies the key that encrypts the health profile. */
fun interface SecretKeyProvider {
    fun key(): SecretKey
}

/**
 * An AES-256 key that lives in the Android Keystore and never leaves it. The
 * app can use it but cannot read it out, and it is not included in backups.
 */
class AndroidKeystoreKeyProvider(private val alias: String = DEFAULT_ALIAS) : SecretKeyProvider {

    override fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "forge_health_profile"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

/**
 * The onboarding health screen, encrypted at rest (blueprint 3.0/3.6: the
 * HealthProfile stays on-device, encrypted, and out of exports).
 *
 * It is kept out of the Room database on purpose: the database holds training
 * data, and this file holds the health answers. The file is AES-256-GCM, with a
 * Keystore key and a fresh IV for every write. It is written atomically, and
 * it belongs in `noBackupFilesDir`: its key can't leave the device, so a
 * backed-up copy could never be decrypted anyway.
 *
 * If the file can't be decrypted (for example the key was lost on a factory
 * reset, or the file is corrupt), it reads as "no profile" and the user
 * re-answers the health screen. The gate is never bypassed.
 */
class HealthProfileStore(
    file: File,
    private val keys: SecretKeyProvider,
) {
    private val atomicFile = AtomicFile(file)

    // AtomicFile isn't safe to read while it's being written: opening the base
    // file for reading deletes an in-progress ".new" file, so a read during a
    // write (say, a recreated activity reading while the clearance answer is
    // saved) can lose the write. Every whole read, write and clear holds the
    // same lock, shared by any stores on the same file.
    private val lock: Mutex = locks.getOrPut(file.absolutePath) { Mutex() }

    suspend fun read(): HealthProfile? = lock.withLock { readUnlocked() }

    suspend fun write(profile: HealthProfile) = lock.withLock { writeUnlocked(profile) }

    suspend fun clear() = lock.withLock { withContext(Dispatchers.IO) { atomicFile.delete() } }

    private suspend fun readUnlocked(): HealthProfile? = withContext(Dispatchers.IO) {
        val bytes = try {
            atomicFile.readFully()
        } catch (_: FileNotFoundException) {
            return@withContext null
        }
        try {
            decode(decrypt(bytes))
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Health profile could not be decrypted; treating it as absent", e)
            null
        } catch (e: Exception) {
            // Truncated or malformed file, or JSON that no longer matches.
            Log.w(TAG, "Health profile is unreadable; treating it as absent", e)
            null
        }
    }

    private suspend fun writeUnlocked(profile: HealthProfile) = withContext(Dispatchers.IO) {
        val bytes = encrypt(encode(profile))
        val stream = atomicFile.startWrite()
        try {
            stream.write(bytes)
            atomicFile.finishWrite(stream)
        } catch (e: Exception) {
            atomicFile.failWrite(stream)
            throw e
        }
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keys.key()) // the provider picks a fresh random IV
        val ciphertext = cipher.doFinal(plaintext)
        val iv = cipher.iv
        return ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeByte(FORMAT_VERSION)
                out.writeByte(iv.size)
                out.write(iv)
                out.write(ciphertext)
            }
            buffer.toByteArray()
        }
    }

    private fun decrypt(bytes: ByteArray): ByteArray {
        val input = DataInputStream(bytes.inputStream())
        val version = input.readUnsignedByte()
        require(version == FORMAT_VERSION) { "Unknown health profile format $version" }
        val iv = ByteArray(input.readUnsignedByte()).also(input::readFully)
        val ciphertext = input.readBytes()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keys.key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    companion object {
        /** The file name, under the app's no-backup directory. */
        const val FILE_NAME = "health_profile.enc"

        private val locks = ConcurrentHashMap<String, Mutex>()

        private const val TAG = "HealthProfileStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val FORMAT_VERSION = 1

        internal fun encode(profile: HealthProfile): ByteArray = JSONObject()
            .put("hasDiagnosedHeartCondition", profile.hasDiagnosedHeartCondition)
            .put("hasHighBloodPressure", profile.hasHighBloodPressure)
            .put("hasExertionalChestPain", profile.hasExertionalChestPain)
            .put("doctorAdvisedAgainstExercise", profile.doctorAdvisedAgainstExercise)
            .put("hasAsthma", profile.hasAsthma)
            .put("hasDiabetes", profile.hasDiabetes)
            .put("takesHeartRateAffectingMedication", profile.takesHeartRateAffectingMedication)
            .put("jointLimitations", JSONArray(profile.jointLimitations.map { it.name }.sorted()))
            .put("injuriesOrSurgeries", profile.injuriesOrSurgeries)
            .put("freeTextLimitations", profile.freeTextLimitations)
            .put("clearanceState", profile.clearanceState.name)
            .put("onboardingComplete", profile.onboardingComplete)
            .toString()
            .toByteArray(Charsets.UTF_8)

        internal fun decode(bytes: ByteArray): HealthProfile {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val joints = json.getJSONArray("jointLimitations")
            return HealthProfile(
                hasDiagnosedHeartCondition = json.getBoolean("hasDiagnosedHeartCondition"),
                hasHighBloodPressure = json.getBoolean("hasHighBloodPressure"),
                hasExertionalChestPain = json.getBoolean("hasExertionalChestPain"),
                doctorAdvisedAgainstExercise = json.getBoolean("doctorAdvisedAgainstExercise"),
                hasAsthma = json.getBoolean("hasAsthma"),
                hasDiabetes = json.getBoolean("hasDiabetes"),
                takesHeartRateAffectingMedication = json.getBoolean("takesHeartRateAffectingMedication"),
                jointLimitations = (0 until joints.length()).map { JointArea.valueOf(joints.getString(it)) }.toSet(),
                injuriesOrSurgeries = json.getString("injuriesOrSurgeries"),
                freeTextLimitations = json.getString("freeTextLimitations"),
                clearanceState = ClearanceState.valueOf(json.getString("clearanceState")),
                onboardingComplete = json.getBoolean("onboardingComplete"),
            )
        }
    }
}
