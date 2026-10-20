// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.intents

import dev.whyoleg.cryptography.random.CryptographyRandom
import xyz.justzappit.evm.hd.EvmKey

/** The empty-intents message `/v0/auth/authenticate` exchanges for a session token, as the intents SDK builds it. */
object IntentsLogin {
    // The SDK uses 60 s; three minutes leaves room for a slow Tor circuit.
    const val DEFAULT_TTL_MILLIS = 180_000L
    private const val MAX_TTL_MILLIS = 600_000L

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
