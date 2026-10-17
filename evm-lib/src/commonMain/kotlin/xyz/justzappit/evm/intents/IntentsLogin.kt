// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.intents

import dev.whyoleg.cryptography.random.CryptographyRandom
import xyz.justzappit.evm.hd.EvmKey

/**
 * The signed message `POST /v0/auth/authenticate` exchanges for a User-Session token: an intents payload
 * with no intents, as `@defuse-protocol/intents-sdk` builds it. Nonce start time and deadline both come
 * from [serverNowMillis], one clock reading, so they can't drift apart.
 */
object IntentsLogin {
    /**
     * The SDK defaults to 60 s. Three minutes passed in the 2026-09-25/26 live tests and leaves room for a
     * slow Tor circuit; the message only needs to outlive the one request that carries it.
     */
    const val DEFAULT_TTL_MILLIS = 180_000L
    private const val MAX_TTL_MILLIS = 600_000L

    /** What `signedData` carries: `{"standard": "erc191", "payload": ..., "signature": ...}`. */
    data class Signed(
        val payload: String,
        val signature: String,
    ) {
        val standard: String get() = Erc191IntentSigner.STANDARD
    }

    fun sign(
        key: EvmKey,
        salt: ByteArray,
        serverNowMillis: Long,
        ttlMillis: Long = DEFAULT_TTL_MILLIS,
        random: ByteArray = CryptographyRandom.Default.nextBytes(IntentNonce.RANDOM_SIZE),
    ): Signed {
        require(ttlMillis in 1..MAX_TTL_MILLIS) { "ttlMillis must be between 1 ms and 10 min" }
        val deadline = serverNowMillis + ttlMillis
        val nonce = IntentNonce.encodeBase64(salt, deadline, serverNowMillis, random)
        val payload = IntentPayload.build(IntentsAccount.accountId(key), deadline, nonce)
        return Signed(payload = payload, signature = Erc191IntentSigner.sign(key, payload))
    }
}
