package co.electriccoin.zcash.ui.preference

import android.util.Base64
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.preference.model.entry.StringPreferenceDefault
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Preference keys stored in [co.electriccoin.zcash.preference.EncryptedPreferenceProvider].
 * Only put sensitive data here — values are encrypted at rest.
 */
object EncryptedPreferenceKeys {
    /**
     * Versioned digest of the user's onboarding PIN. Empty string means no PIN has
     * been set. The current format is `v2$<salt-base64>$<pbkdf2-base64>`; legacy
     * installs may still hold a bare 64-char SHA-256 hex digest, which is upgraded
     * to v2 in-place on first successful verify (see [verifyAndUpgradePin]).
     */
    val APP_PIN_HASH =
        StringPreferenceDefault(
            key = PreferenceKey("app_pin_hash"),
            defaultValue = ""
        )

    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val PBKDF2_ITERATIONS = 100_000
    private const val PBKDF2_KEY_LENGTH_BITS = 256
    private const val PBKDF2_SALT_BYTES = 16
    private const val V2_PREFIX = "v2$"
    private const val V2_DELIMITER = "$"
    private const val BASE64_FLAGS = Base64.NO_WRAP or Base64.NO_PADDING

    /**
     * Hashes [pin] with PBKDF2-HMAC-SHA256 (100k iterations, 16-byte random salt) and
     * returns the encoded `v2$<salt>$<hash>` string suitable for direct storage in
     * [APP_PIN_HASH]. Use this for both new PIN setup and post-verify upgrades.
     */
    fun hashPinV2(pin: String): String {
        val salt = ByteArray(PBKDF2_SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(pin, salt)
        return V2_PREFIX +
            Base64.encodeToString(salt, BASE64_FLAGS) + V2_DELIMITER +
            Base64.encodeToString(hash, BASE64_FLAGS)
    }

    /**
     * Verifies [pin] against the stored hash. If the stored value is in the legacy
     * SHA-256 format and verification succeeds, the hash is silently re-written in
     * v2 format so subsequent attempts use the stronger derivation. Comparisons are
     * performed in constant time via [MessageDigest.isEqual].
     */
    suspend fun verifyAndUpgradePin(
        pin: String,
        encryptedPreferenceProvider: EncryptedPreferenceProvider,
    ): Boolean {
        val provider = encryptedPreferenceProvider()
        val stored = APP_PIN_HASH.getValue(provider)
        if (stored.isEmpty()) return false

        val matches =
            if (stored.startsWith(V2_PREFIX)) {
                verifyV2(pin, stored)
            } else {
                verifyLegacySha256(pin, stored)
            }

        if (matches && !stored.startsWith(V2_PREFIX)) {
            APP_PIN_HASH.putValue(provider, hashPinV2(pin))
        }

        return matches
    }

    private fun verifyV2(pin: String, stored: String): Boolean {
        val parts = stored.removePrefix(V2_PREFIX).split(V2_DELIMITER)
        if (parts.size != 2) return false
        return runCatching {
            val salt = Base64.decode(parts[0], BASE64_FLAGS)
            val expected = Base64.decode(parts[1], BASE64_FLAGS)
            val computed = pbkdf2(pin, salt)
            MessageDigest.isEqual(computed, expected)
        }.getOrDefault(false)
    }

    private fun verifyLegacySha256(pin: String, stored: String): Boolean {
        val computed =
            MessageDigest
                .getInstance("SHA-256")
                .digest(pin.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(computed.toByteArray(Charsets.UTF_8), stored.toByteArray(Charsets.UTF_8))
    }

    private fun pbkdf2(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
