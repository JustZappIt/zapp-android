package co.electriccoin.zcash.ui.common.push

import android.content.Context
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Stable per-install Web-Push identity for the embedded doorbell receiver:
 * - a P-256 public key (`p256dh`) the blind peer encrypts the ping to,
 * - a 16-byte `auth` secret (RFC 8291),
 * - the ntfy `topic` we listen on.
 *
 * Generated once and persisted so the registered endpoint stays stable across
 * restarts. We never decrypt (the doorbell carries no content), so the private
 * key is generated only to obtain a valid public point, then discarded.
 */
class PushKeys(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile private var cached: Keys? = null

    val topic: String get() = ensure().topic
    val p256dh: String get() = ensure().p256dh
    val auth: String get() = ensure().auth

    @Synchronized
    private fun ensure(): Keys {
        cached?.let { return it }
        val keys = read() ?: generate().also { write(it) }
        cached = keys
        return keys
    }

    private fun read(): Keys? {
        val topic = prefs.getString(KEY_TOPIC, null)
        val p256dh = prefs.getString(KEY_P256DH, null)
        val auth = prefs.getString(KEY_AUTH, null)
        return if (topic != null && p256dh != null && auth != null) {
            Keys(topic, p256dh, auth)
        } else {
            null
        }
    }

    private fun write(keys: Keys) {
        prefs
            .edit()
            .putString(KEY_TOPIC, keys.topic)
            .putString(KEY_P256DH, keys.p256dh)
            .putString(KEY_AUTH, keys.auth)
            .apply()
    }

    private fun generate(): Keys {
        val keyPair =
            KeyPairGenerator.getInstance("EC").run {
                initialize(ECGenParameterSpec("secp256r1"))
                generateKeyPair()
            }
        val p256dh = base64Url(uncompressedPoint(keyPair.public as ECPublicKey))
        val auth = base64Url(ByteArray(AUTH_LEN).also { SecureRandom().nextBytes(it) })
        val topic = "up" + ByteArray(TOPIC_BYTES).also { SecureRandom().nextBytes(it) }.toHex()
        return Keys(topic, p256dh, auth)
    }

    // Uncompressed EC point: 0x04 || X(32) || Y(32).
    private fun uncompressedPoint(key: ECPublicKey): ByteArray {
        val out = ByteArray(UNCOMPRESSED_POINT_BYTES)
        out[POINT_FORMAT_OFFSET] = UNCOMPRESSED_POINT_PREFIX
        fixedCoordinate(key.w.affineX.toByteArray()).copyInto(out, destinationOffset = X_COORDINATE_OFFSET)
        fixedCoordinate(key.w.affineY.toByteArray()).copyInto(out, destinationOffset = Y_COORDINATE_OFFSET)
        return out
    }

    // BigInteger.toByteArray() may carry a leading sign byte or be shorter; left-pad to the EC coordinate size.
    private fun fixedCoordinate(bytes: ByteArray): ByteArray =
        when {
            bytes.size == EC_COORDINATE_BYTES -> {
                bytes
            }

            bytes.size > EC_COORDINATE_BYTES -> {
                bytes.copyOfRange(
                    fromIndex = bytes.size - EC_COORDINATE_BYTES,
                    toIndex = bytes.size,
                )
            }

            else -> {
                ByteArray(EC_COORDINATE_BYTES).also {
                    bytes.copyInto(it, destinationOffset = EC_COORDINATE_BYTES - bytes.size)
                }
            }
        }

    private fun base64Url(bytes: ByteArray): String = Base64.encodeToString(bytes, BASE64_FLAGS)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private data class Keys(
        val topic: String,
        val p256dh: String,
        val auth: String,
    )

    private companion object {
        const val PREFS = "zapp_push"
        const val KEY_TOPIC = "topic"
        const val KEY_P256DH = "p256dh"
        const val KEY_AUTH = "auth"
        const val AUTH_LEN = 16
        const val TOPIC_BYTES = 12
        const val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        const val POINT_FORMAT_OFFSET = 0
        const val UNCOMPRESSED_POINT_PREFIX: Byte = 0x04
        const val EC_COORDINATE_BYTES = 32
        const val X_COORDINATE_OFFSET = 1
        const val Y_COORDINATE_OFFSET = X_COORDINATE_OFFSET + EC_COORDINATE_BYTES
        const val UNCOMPRESSED_POINT_BYTES = Y_COORDINATE_OFFSET + EC_COORDINATE_BYTES
    }
}
