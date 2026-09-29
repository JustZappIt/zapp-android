// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.identity

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.util.hexToBytes

/**
 * One signed attestation, as the widget's `/v1/widget/attestation` returns it and as the
 * ReputationManager's `submit…Attestation` consumes it. The signature binds the wallet the session
 * was opened for, so only that wallet can submit it.
 */
class IdentityAttestation(
    val nullifier: ByteArray,
    val limit: BigInteger,
    /** Unix seconds; the submit must land before this. */
    val expiry: BigInteger,
    val signature: ByteArray,
) {
    init {
        require(nullifier.size == NULLIFIER_BYTES) { "nullifier must be 32 bytes, got ${nullifier.size}" }
        require(signature.size == SIGNATURE_BYTES) { "signature must be 65 bytes, got ${signature.size}" }
        require(limit.signum() >= 0) { "limit must be non-negative" }
        require(expiry.signum() > 0) { "expiry must be positive" }
    }

    companion object {
        const val NULLIFIER_BYTES = 32
        const val SIGNATURE_BYTES = 65

        /** Throws [IllegalArgumentException] on anything the contract could not consume. */
        fun fromJson(json: JsonObject): IdentityAttestation {
            fun field(name: String): String =
                requireNotNull(json[name]?.jsonPrimitive?.content) { "attestation has no $name" }
            return IdentityAttestation(
                nullifier = field("nullifier").hexToBytes(),
                limit = BigInteger(field("limit")),
                expiry = BigInteger(field("expiry")),
                signature = field("signature").hexToBytes(),
            )
        }
    }
}
