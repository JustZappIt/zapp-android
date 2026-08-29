// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.reclaim

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.hexToBytes

/**
 * A proof as Reclaim's session endpoint returns it. Fields the contract does not read are kept
 * where they carry diagnostic weight — [publicData] is how a provider says "this account doesn't
 * qualify" (§4.9), and it says it inside a *successful* response.
 */
@Serializable
data class ReclaimSessionProof(
    val identifier: String = "",
    val claimData: ReclaimClaimData,
    val signatures: List<String> = emptyList(),
    val witnesses: List<JsonObject> = emptyList(),
    /**
     * Empty when the account failed the provider's own criteria — the year-old-account rule on X,
     * GitHub and Instagram. Reclaim reports that as a proof, not as an error, and the contract
     * would reject it much later with nothing the user can act on.
     */
    val publicData: JsonObject? = null,
)

@Serializable
data class ReclaimClaimData(
    val provider: String,
    val parameters: String,
    val owner: String,
    val timestampS: Long,
    val context: String,
    val identifier: String,
    val epoch: Long,
)

/** The session document `GET /api/sdk/session/{id}` returns. */
@Serializable
data class ReclaimSessionStatus(
    val session: ReclaimSessionBody? = null,
    val message: String? = null,
)

@Serializable
data class ReclaimSessionBody(
    val proofs: List<ReclaimSessionProof> = emptyList(),
    val statusV2: String? = null,
)

/**
 * The `Proof` tuple `socialVerify` takes. Named for the Solidity struct rather than the wire shape
 * because that is the only thing it exists to become.
 */
data class OnChainProof(
    val provider: String,
    val parameters: String,
    val context: String,
    val identifier: ByteArray,
    val owner: Address,
    val timestampS: Long,
    val epoch: Long,
    val signatures: List<ByteArray>,
) {
    init {
        require(identifier.size == IDENTIFIER_BYTES) {
            "claim identifier must be 32 bytes, got ${identifier.size}"
        }
        require(signatures.isNotEmpty()) { "a claim with no attestor signature proves nothing" }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is OnChainProof &&
                    provider == other.provider &&
                    parameters == other.parameters &&
                    context == other.context &&
                    identifier.contentEquals(other.identifier) &&
                    owner == other.owner &&
                    timestampS == other.timestampS &&
                    epoch == other.epoch &&
                    signatures.size == other.signatures.size &&
                    signatures.indices.all { signatures[it].contentEquals(other.signatures[it]) }
            )

    override fun hashCode(): Int {
        var result = provider.hashCode()
        result = HASH_MULTIPLIER * result + parameters.hashCode()
        result = HASH_MULTIPLIER * result + context.hashCode()
        result = HASH_MULTIPLIER * result + identifier.contentHashCode()
        result = HASH_MULTIPLIER * result + owner.hashCode()
        result = HASH_MULTIPLIER * result + timestampS.hashCode()
        result = HASH_MULTIPLIER * result + epoch.hashCode()
        result = HASH_MULTIPLIER * result + signatures.sumOf { it.contentHashCode() }
        return result
    }

    private companion object {
        const val IDENTIFIER_BYTES = 32
        const val HASH_MULTIPLIER = 31
    }
}

/**
 * Reclaim's `transformForOnchain`, in Kotlin.
 *
 * The reshuffle is small and entirely positional: `claimData` splits into the ClaimInfo the
 * verifier hashes (`provider`, `parameters`, `context`) and the CompleteClaimData it recovers
 * signatures against (`identifier`, `owner`, `timestampS`, `epoch`). Getting a field into the
 * wrong half produces a proof that verifies against nothing, and the revert names no cause.
 */
object ReclaimProofTransform {
    fun toOnChain(proof: ReclaimSessionProof): OnChainProof =
        OnChainProof(
            provider = proof.claimData.provider,
            parameters = proof.claimData.parameters,
            context = proof.claimData.context,
            identifier = proof.claimData.identifier.hexToBytes(),
            owner = Address.parse(proof.claimData.owner),
            timestampS = proof.claimData.timestampS,
            epoch = proof.claimData.epoch,
            signatures = proof.signatures.map { it.hexToBytes() },
        )

    /**
     * True when the provider ran but the account did not qualify. Reclaim answers with a proof
     * whose first entry carries an empty `publicData`, so this is checked for every age-gated
     * provider rather than only for GitHub, which is all the SDK special-cases.
     */
    fun isCriteriaNotMet(proofs: List<ReclaimSessionProof>): Boolean {
        val first = proofs.firstOrNull() ?: return false
        return first.publicData?.isEmpty() == true
    }
}
