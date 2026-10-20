// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.intents

import xyz.justzappit.evm.util.hexToBytes
import kotlin.io.encoding.Base64

/**
 * The 32-byte nonce `intents.near` expects, as the intents SDK's `VersionedNonceBuilder` encodes it:
 * magic | version | salt[4] | deadline ns (u64 LE) | start ns (u64 LE) | random[7]. The server rejects a start
 * time far from its own clock, so callers pass the server's time.
 */
object IntentNonce {
    const val SIZE = 32
    const val SALT_SIZE = 4
    const val RANDOM_SIZE = 7
    private val MAGIC = "5628f6c6".hexToBytes()
    private const val VERSION: Byte = 0
    private const val NANOS_PER_MILLI = 1_000_000L
    private const val SALT_HEX_LENGTH = SALT_SIZE * 2

    // 9999-12-31, the last date Date.toISOString writes without a sign.
    internal const val MAX_EPOCH_MILLIS = 253_402_300_799_999L

    // Nanoseconds in a signed Long end in 2262; a later deadline would wrap.
    internal const val MAX_NONCE_EPOCH_MILLIS = Long.MAX_VALUE / NANOS_PER_MILLI

    /** The salt from `intents.near`'s `current_salt` view, e.g. `252812b3`. */
    fun parseSalt(hex: String): ByteArray {
        require(hex.length == SALT_HEX_LENGTH && hex.all { it in '0'..'9' || it in 'a'..'f' }) {
            "salt must be $SALT_HEX_LENGTH lowercase hex characters"
        }
        return hex.hexToBytes()
    }

    internal fun encode(
        salt: ByteArray,
        deadlineEpochMillis: Long,
        startEpochMillis: Long,
        random: ByteArray,
    ): ByteArray {
        require(salt.size == SALT_SIZE) { "salt must be $SALT_SIZE bytes" }
        require(random.size == RANDOM_SIZE) { "random must be $RANDOM_SIZE bytes" }
        require(startEpochMillis > 0) { "start must be positive" }
        require(deadlineEpochMillis > startEpochMillis) { "deadline must be after start" }
        require(deadlineEpochMillis <= MAX_NONCE_EPOCH_MILLIS) { "deadline out of range" }
        return MAGIC +
            byteArrayOf(VERSION) +
            salt +
            (deadlineEpochMillis * NANOS_PER_MILLI).toLittleEndian() +
            (startEpochMillis * NANOS_PER_MILLI).toLittleEndian() +
            random
    }

    internal fun encodeBase64(
        salt: ByteArray,
        deadlineEpochMillis: Long,
        startEpochMillis: Long,
        random: ByteArray,
    ): String = Base64.encode(encode(salt, deadlineEpochMillis, startEpochMillis, random))

    private fun Long.toLittleEndian(): ByteArray =
        ByteArray(Long.SIZE_BYTES) { i -> (this ushr (Byte.SIZE_BITS * i)).toByte() }
}
