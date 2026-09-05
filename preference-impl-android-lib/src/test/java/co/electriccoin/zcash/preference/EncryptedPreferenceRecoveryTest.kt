package co.electriccoin.zcash.preference

import java.io.CharConversionException
import java.io.IOException
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The classification that decides whether opening the encrypted preferences may destroy them.
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

/** Stands in for Tink's shaded exception, which [isUnrecoverableCorruption] matches by simple name. */
private class InvalidProtocolBufferException(
    message: String
) : Exception(message)
