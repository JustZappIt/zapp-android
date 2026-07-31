// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlinx.serialization.Serializable
import xyz.justzappit.evm.hd.EvmKeyDerivation
import xyz.justzappit.evm.util.toHex

@Serializable
data class RelayIdentity(
    val privateKeyHex: String,
    val publicKeyHex: String,
)

object RelayIdentities {
    private const val FIELD_BYTES = 32
    private const val MAX_RETRIES = 100

    fun generate(): RelayIdentity {
        val buf = ByteArray(FIELD_BYTES)
        repeat(MAX_RETRIES) {
            CryptographyRandom.Default.nextBytes(buf)
            runCatching { EvmKeyDerivation.fromPrivateKey(buf) }
                .getOrNull()
                ?.let { key ->
                    val privBytes = key.exportPrivateKeyBytes()
                    try {
                        return RelayIdentity(
                            privateKeyHex = "0x" + privBytes.toHex(),
                            publicKeyHex = key.publicKey.toHex(),
                        )
                    } finally {
                        privBytes.fill(0)
                    }
                }
        }
        error("Exhausted $MAX_RETRIES attempts to generate a relay identity")
    }
}
