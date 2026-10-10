// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.intents

import xyz.justzappit.evm.util.hexToBytes
import kotlin.io.encoding.Base64

/**
 * The 32-byte versioned, expirable nonce that `intents.near` expects, byte-for-byte what
 * `VersionedNonceBuilder.encodeNonce` in `@defuse-protocol/intents-sdk` 0.88.0 builds:
 *
 * ```
 * magic 5628f6c6 | version 00 | salt[4] | deadline ns (u64 LE) | start ns (u64 LE) | random[7]
 * ```
 *
 * The start timestamp at the head of the 15 "random" bytes is what the SDK's
 * `createTimestampedNonceBytes` adds. `/v0/auth/authenticate` rejects a nonce without it
 * ("timestamp validation failed"), and on 2026-09-26 accepted start times from about 240 s before
 * to 300 s after server time, so callers should pass the server's clock, not the device's.
 */
object IntentNonce {
    const val SIZE = 32
    const val SALT_SIZE = 4
    const val RANDOM_SIZE = 7
    private val MAGIC = "5628f6c6".hexToBytes()
    private const val VERSION: Byte = 0
    private const val NANOS_PER_MILLI = 1_000_000L
    private const val SALT_HEX_LENGTH = SALT_SIZE * 2

    // Nanoseconds overflow a u64 past this; also caps any timestamp at 9999-12-31, the last date
    // Date.toISOString writes without a sign.
    internal const val MAX_EPOCH_MILLIS = 253_402_300_799_999L

    /**
     * The 4-byte salt from `intents.near`'s `current_salt` view, which returns a hex string such as
     * `"252812b3"` (after the view's JSON quotes are removed).
     */
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
        require(deadlineEpochMillis <= MAX_EPOCH_MILLIS) { "deadline out of range" }
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
