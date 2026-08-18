// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.justzappit.evm.crypto.Ecies
import xyz.justzappit.evm.crypto.Encrypted

/**
 * Mirrors `@p2pdotme/sdk` decryptPaymentAddress:
 *  - compact SDK ciphertexts decrypt to a signed `{message, signature}` JSON payload
 *  - legacy eth-crypto JSON envelopes decrypt directly to the raw payment address
 *
 * Android used to return the raw ECIES plaintext, which is wrong for current SDK payloads.
 */
internal object PaymentAddressDecryptor {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    fun decrypt(ciphertext: String, relay: RelayIdentity?): String? {
        if (ciphertext.isBlank() || relay == null) return null
        return runCatching {
            val legacyEnvelope = parseLegacyEnvelope(ciphertext)
            val encrypted = legacyEnvelope ?: Ecies.cipherParse(ciphertext)
            val plaintext = Ecies.decryptWithPrivateKey(relay.privateKeyHex, encrypted)
            if (legacyEnvelope != null) plaintext else unwrapSignedPayload(plaintext) ?: plaintext
        }.getOrNull()
    }

    private fun parseLegacyEnvelope(input: String): Encrypted? {
        if (!input.trimStart().startsWith("{")) return null
        return runCatching {
            val envelope = json.decodeFromString(LegacyEciesEnvelope.serializer(), input)
            Encrypted(
                iv = envelope.iv,
                ephemPublicKey = envelope.ephemPublicKey,
                ciphertext = envelope.ciphertext,
                mac = envelope.mac,
            )
        }.getOrNull()
    }

    private fun unwrapSignedPayload(plaintext: String): String? =
        runCatching {
            json.decodeFromString(SignedPaymentAddressPayload.serializer(), plaintext).message
        }.getOrNull()
}

@Serializable
private data class LegacyEciesEnvelope(
    val iv: String,
    val ephemPublicKey: String,
    val ciphertext: String,
    val mac: String,
)

@Serializable
private data class SignedPaymentAddressPayload(
    val message: String,
    val signature: String,
)
