// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.liveness

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.hexToBytes

/**
 * One signed `LivenessAttestation(address wallet,bytes32 nullifier,uint256 limit,uint256 expiry)`,
 * as the service returns it and as the integrator's `submitLivenessAttestation` consumes it.
 */
class LivenessAttestation(
    val wallet: Address,
    val nullifier: ByteArray,
    /** Micro-USDC. The contract clamps it to its own cap, so this is an upper bound, not a promise. */
    val limit: BigInteger,
    /** Unix seconds; the claim must land before this. */
    val expiry: Long,
    val signature: ByteArray,
    val attestor: Address,
) {
    init {
        require(nullifier.size == NULLIFIER_BYTES) { "nullifier must be 32 bytes, got ${nullifier.size}" }
        require(signature.size == SIGNATURE_BYTES) { "signature must be 65 bytes, got ${signature.size}" }
        require(limit.signum() >= 0) { "limit must be non-negative" }
    }

    companion object {
        const val NULLIFIER_BYTES = 32
        const val SIGNATURE_BYTES = 65

        /** Throws [IllegalArgumentException] on anything the contract could not consume. */
        fun fromJson(json: JsonObject): LivenessAttestation {
            val message = requireNotNull(json["message"]?.jsonObject) { "attestation has no message" }

            fun field(name: String): String =
                requireNotNull(message[name]?.jsonPrimitive?.content) { "attestation message has no $name" }
            return LivenessAttestation(
                wallet = Address.parse(field("wallet")),
                nullifier = field("nullifier").hexToBytes(),
                limit = BigInteger(field("limit")),
                expiry = field("expiry").toLong(),
                signature =
                    requireNotNull(json["signature"]?.jsonPrimitive?.content) { "attestation has no signature" }
                        .hexToBytes(),
                attestor =
                    Address.parse(
                        requireNotNull(json["attestor"]?.jsonPrimitive?.content) { "attestation has no attestor" },
                    ),
            )
        }
    }
}
