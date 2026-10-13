// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 The Zapp Contributors

package xyz.justzappit.offramp.identity

import kotlinx.serialization.Serializable
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.p2p.CurrencyCode

/** Durable, private storage. Writes must finish before returning; a failed write must throw. */
interface IdentityVerificationStore {
    suspend fun get(key: String): PendingIdentityVerification?

    suspend fun set(key: String, pending: PendingIdentityVerification?)
}

/** The key binds the wallet, chain, contract and check; state binds the browser session. */
@Serializable
data class PendingIdentityVerification(
    val state: String,
    val currency: CurrencyCode,
    val expiresAtSeconds: Long,
    val code: String? = null,
    val attestation: StoredIdentityAttestation? = null,
    val transactionHash: String? = null,
    val transactionNonce: String? = null,
    val receiptBlock: String? = null,
) {
    init {
        require(state.isNotBlank()) { "session state must not be blank" }
        require(expiresAtSeconds > 0) { "session must expire" }
        require((transactionHash == null) == (transactionNonce == null)) {
            "transaction hash and nonce must travel together"
        }
        require(receiptBlock == null || transactionHash != null) { "receipt must belong to a transaction" }
        require(transactionHash == null || attestation != null) { "transaction must retain its attestation" }
    }

    val canRecover: Boolean get() = code != null || attestation != null || transactionHash != null
}

/** Strings preserve uint256 values exactly on every platform. No face or document data is stored. */
@Serializable
data class StoredIdentityAttestation(
    val nullifier: String,
    val limit: String,
    val expiry: String,
    val signature: String,
) {
    fun decode(): IdentityAttestation =
        IdentityAttestation(nullifier.hexToBytes(), BigInteger(limit), BigInteger(expiry), signature.hexToBytes())

    companion object {
        fun from(attestation: IdentityAttestation): StoredIdentityAttestation =
            StoredIdentityAttestation(
                nullifier = attestation.nullifier.toHex(),
                limit = attestation.limit.toString(),
                expiry = attestation.expiry.toString(),
                signature = attestation.signature.toHex(),
            )
    }
}
