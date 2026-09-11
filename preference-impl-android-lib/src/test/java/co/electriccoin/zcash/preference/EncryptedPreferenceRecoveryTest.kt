package co.electriccoin.zcash.preference

import java.io.CharConversionException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import co.electriccoin.zcash.preference.androidsecurity.KeyStoreException as AndroidKeyStoreException
import co.electriccoin.zcash.preference.androidsecurity.legacy.KeyStoreException as LegacyAndroidKeyStoreException

/**
 * The classification that decides whether opening the encrypted preferences may destroy them,
 * and whether that destruction may take the shared Keystore master key with it.
 *
 * The instrumented [EncryptedPreferenceProviderTest] covers the other direction — that a genuinely
 * orphaned file is still recovered — but it passes against the old catch-all too, because wiping
 * everything also produces empty preferences. Only this pins the part that stops a transient
 * failure from taking the seed phrase with it.
 */
class EncryptedPreferenceRecoveryTest {
    @Test
    fun aeadAuthenticationFailureIsUnrecoverable() {
        assertTrue(isUnrecoverableCorruption(AEADBadTagException("tag mismatch")))
        assertTrue(isUnrecoverableCorruption(BadPaddingException("bad padding")))
    }

    @Test
    fun tinkKeysetParseFailuresAreUnrecoverable() {
        assertTrue(isUnrecoverableCorruption(CharConversionException("malformed hex")))
        assertTrue(isUnrecoverableCorruption(InvalidProtocolBufferException("malformed proto")))
    }

    @Test
    fun keystoreSelfTestAndInvalidKeyAreUnrecoverable() {
        assertTrue(isUnrecoverableCorruption(KeyStoreException("validateAead failed")))
        assertTrue(isUnrecoverableCorruption(InvalidKeyException("no such key")))
    }

    /**
     * The regression. A Keystore that is merely unavailable — busy, mid-update, or throwing a
     * vendor [ProviderException] — must never authorize deleting the stored wallet.
     */
    @Test
    fun transientFailuresAreRecoverable() {
        assertFalse(isUnrecoverableCorruption(IOException("keystore busy")))
        assertFalse(isUnrecoverableCorruption(ProviderException("Keystore operation failed")))
        assertFalse(isUnrecoverableCorruption(IllegalStateException("not initialized")))
        assertFalse(isUnrecoverableCorruption(SecurityException("permission denied")))
    }

    /**
     * The shape AOSP gives a wedged Keystore daemon: `InvalidKeyException("Keystore operation
     * failed")` caused by `android.security.KeyStoreException`, which Tink then wraps as "the
     * master key exists but is unusable". Both bare types are on the corruption allowlist, so
     * without the veto this deleted the seed over a failure that heals on the next launch.
     */
    @Test
    fun transientKeystoreFailureWrappedByAospIsNotCorruption() {
        val wedged = InvalidKeyException("Keystore operation failed", AndroidKeyStoreException())

        assertFalse(isUnrecoverableCorruption(wedged))
        assertFalse(isUnrecoverableCorruption(KeyStoreException("the master key exists but is unusable", wedged)))
        assertFalse(isUnrecoverableCorruption(GeneralSecurityException(wedged)))
        assertFalse(isMasterKeyFailure(wedged))
        assertFalse(isMasterKeyFailure(KeyStoreException("the master key exists but is unusable", wedged)))
    }

    /**
     * The shape a device-to-device transfer actually leaves behind: `MasterKey.Builder.build()`
     * mints a replacement key under the old alias, so the stored ciphertext no longer
     * authenticates and the Keystore says so. The marker is present but the failure will never
     * heal, so it must still count as corruption.
     */
    @Test
    fun keystoreFailureThatCannotAuthenticateCiphertextIsCorruption() {
        val unauthenticated =
            aeadFailureCausedBy(
                AndroidKeyStoreException(
                    "Signature/MAC verification failed (internal Keystore code: -30)",
                    numericErrorCode = AndroidKeyStoreException.ERROR_KEYMINT_FAILURE
                )
            )

        assertTrue(isUnrecoverableCorruption(unauthenticated))
        assertFalse(isMasterKeyFailure(unauthenticated))
    }

    @Test
    fun keystoreFailureReportingMissingKeyIsCorruptionAndMasterKeyFailure() {
        val keyNotFound =
            InvalidKeyException(
                "Keystore operation failed",
                AndroidKeyStoreException(
                    "Key not found",
                    numericErrorCode = AndroidKeyStoreException.ERROR_KEY_DOES_NOT_EXIST
                )
            )

        assertTrue(isUnrecoverableCorruption(keyNotFound))
        assertTrue(isMasterKeyFailure(keyNotFound))
    }

    @Test
    fun keystoreFailureReportingPermanentlyInvalidatedKeyIsCorruptionAndMasterKeyFailure() {
        val permanentlyInvalidated =
            InvalidKeyException(
                "Keystore operation failed",
                AndroidKeyStoreException(
                    "Key permanently invalidated",
                    numericErrorCode = AndroidKeyStoreException.ERROR_KEY_DOES_NOT_EXIST
                )
            )

        assertTrue(isUnrecoverableCorruption(permanentlyInvalidated))
        assertTrue(isMasterKeyFailure(permanentlyInvalidated))
    }

    /** AOSP falls back to the bare error code as the message for a condition it has no wording for. */
    @Test
    fun api33ErrorCodeIdentifiesLostKeyWithoutRecognizableMessage() {
        assertTrue(
            isUnrecoverableCorruption(
                aeadFailureCausedBy(
                    AndroidKeyStoreException("7", numericErrorCode = AndroidKeyStoreException.ERROR_KEY_CORRUPTED)
                )
            )
        )
    }

    /** Below API 33 the class carries no classification methods, so the message stands alone. */
    @Test
    fun preApi33KeystoreFailureIsClassifiedFromMessageAlone() {
        assertTrue(
            isUnrecoverableCorruption(
                aeadFailureCausedBy(LegacyAndroidKeyStoreException("Signature/MAC verification failed"))
            )
        )
        assertFalse(
            isUnrecoverableCorruption(
                InvalidKeyException("Keystore operation failed", LegacyAndroidKeyStoreException("System error"))
            )
        )
    }

    @Test
    fun keystoreFailureAospCallsTransientIsNeverPermanent() {
        val secureHardwareBusy =
            InvalidKeyException(
                "Keystore operation failed",
                AndroidKeyStoreException("Secure hardware busy", isTransientFailure = true)
            )

        assertFalse(isUnrecoverableCorruption(secureHardwareBusy))
        assertFalse(isMasterKeyFailure(secureHardwareBusy))
    }

    @Test
    fun transientVerdictOutranksPermanentSoundingMessage() {
        assertFalse(
            isUnrecoverableCorruption(
                aeadFailureCausedBy(
                    AndroidKeyStoreException("Signature/MAC verification failed", isTransientFailure = true)
                )
            )
        )
    }

    @Test
    fun classificationLooksThroughTheCauseChain() {
        val wrapped = RuntimeException("open failed", IllegalStateException("inner", BadPaddingException("root")))
        assertTrue(isUnrecoverableCorruption(wrapped))
    }

    /** A cause chain longer than the limit must not be walked forever, nor read as corruption. */
    @Test
    fun causeChainIsBounded() {
        var deep: Throwable = BadPaddingException("root")
        repeat(20) { deep = RuntimeException("layer", deep) }
        assertFalse(isUnrecoverableCorruption(RuntimeException("outer", deep)))
    }

    @Test
    fun cyclicCauseChainTerminates() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertFalse(isUnrecoverableCorruption(a))
    }

    @Test
    fun keystoreKeyFailuresAreMasterKeyFailures() {
        assertTrue(isMasterKeyFailure(InvalidKeyException("Failed to unwrap key")))
        assertTrue(isMasterKeyFailure(GeneralSecurityException(InvalidKeyException())))
        assertTrue(isMasterKeyFailure(KeyStoreException()))
        assertTrue(isMasterKeyFailure(GeneralSecurityException(KeyStoreException())))
        assertTrue(isMasterKeyFailure(InvalidKeyException("Keystore operation failed", KeyStoreException())))
    }

    /** Recreating a store over one of these must leave the shared master key alone. */
    @Test
    fun dataLevelFailuresAreNotMasterKeyFailures() {
        assertFalse(isMasterKeyFailure(AEADBadTagException()))
        assertFalse(isMasterKeyFailure(BadPaddingException()))
        assertFalse(isMasterKeyFailure(CharConversionException()))
        assertFalse(isMasterKeyFailure(InvalidProtocolBufferException("malformed proto")))
        assertFalse(isMasterKeyFailure(IOException()))
    }

    @Test
    fun deleteEncryptedPreferencesFilesTakesTheBackupAlong() =
        withTempDirectory { dir ->
            val xml = File(dir, "store.xml").apply { writeText("<map/>") }
            val bak = File(dir, "store.xml.bak").apply { writeText("<map/>") }
            val other = File(dir, "other.xml").apply { writeText("<map/>") }

            assertTrue(deleteEncryptedPreferencesFiles(dir, "store"))
            assertFalse(xml.exists())
            assertFalse(bak.exists())
            assertTrue(other.exists())
        }

    @Test
    fun deleteEncryptedPreferencesFilesWithNothingToDeleteSucceeds() =
        withTempDirectory { dir ->
            assertTrue(deleteEncryptedPreferencesFiles(dir, "store"))
        }

    /** A file that stays behind must be reported, or the master key would be deleted over it. */
    @Test
    fun deleteEncryptedPreferencesFilesReportsAFileThatStays() =
        withTempDirectory { dir ->
            val xml = File(dir, "store.xml").apply { writeText("<map/>") }
            dir.setWritable(false)
            try {
                // Permissions are not enforced for a privileged user; nothing to pin then.
                if (runCatching { File(dir, "probe").createNewFile() }.getOrDefault(false)) {
                    return@withTempDirectory
                }

                assertFalse(deleteEncryptedPreferencesFiles(dir, "store"))
                assertTrue(xml.exists())
            } finally {
                dir.setWritable(true)
            }
        }

    @Test
    fun retryOnceOrDefaultReturnsFirstSuccess() {
        var calls = 0
        val result =
            retryOnceOrDefault(false) {
                calls++
                true
            }
        assertTrue(result)
        assertEquals(1, calls)
    }

    @Test
    fun retryOnceOrDefaultRetriesOnceThenSucceeds() {
        var calls = 0
        val result =
            retryOnceOrDefault(false) {
                calls++
                if (calls == 1) error("transient") else true
            }
        assertTrue(result)
        assertEquals(2, calls)
    }

    /** Two failures mean the Keystore state is unknown, which must read as "not orphaned". */
    @Test
    fun retryOnceOrDefaultFallsBackAfterTwoFailures() {
        var calls = 0
        val result =
            retryOnceOrDefault(false) {
                calls++
                error("still down")
            }
        assertFalse(result)
        assertEquals(2, calls)
    }
}

/**
 * The AEAD failure a Keystore raises through `AndroidKeyStoreCipherSpiBase.engineDoFinal`, which
 * chains the Keystore exception as the cause rather than passing it to a constructor.
 */
private fun aeadFailureCausedBy(cause: Throwable): AEADBadTagException =
    AEADBadTagException("decryption failed").apply { initCause(cause) }

private fun withTempDirectory(block: (File) -> Unit) {
    val dir = Files.createTempDirectory("shared_prefs").toFile()
    try {
        block(dir)
    } finally {
        dir.deleteRecursively()
    }
}

/** Stands in for Tink's shaded exception, which [isUnrecoverableCorruption] matches by simple name. */
private class InvalidProtocolBufferException(
    message: String
) : Exception(message)
