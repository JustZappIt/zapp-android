// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.railgun

import java.math.BigInteger
import kotlin.time.Duration

enum class RailgunNetwork(
    internal val wireName: String
) {
    SEPOLIA("sepolia"),
    MAINNET("mainnet"),
}

/**
 * Where a note stands with Railgun's screening. A shield waits in [SHIELD_PENDING] until the
 * screening nodes pass it (an hour on mainnet, a minute on testnets) and only [SPENDABLE] can be
 * sent or unshielded; [SHIELD_BLOCKED] funds can only go back to the address that shielded them.
 */
enum class RailgunBalanceBucket(
    internal val wireName: String
) {
    SPENDABLE("Spendable"),
    SHIELD_PENDING("ShieldPending"),
    SHIELD_BLOCKED("ShieldBlocked"),
    PROOF_SUBMITTED("ProofSubmitted"),
    MISSING_INTERNAL_POI("MissingInternalPOI"),
    MISSING_EXTERNAL_POI("MissingExternalPOI"),
    SPENT("Spent"),
}

data class RailgunTokenAmount(
    val token: String,
    val amount: BigInteger,
)

data class RailgunBalances(
    val byBucket: Map<RailgunBalanceBucket, List<RailgunTokenAmount>>
)

data class RailgunGasAccount(
    val address: String,
    val balance: BigInteger,
)

data class RailgunSent(
    val txHash: String,
    val proofDuration: Duration?,
)

enum class RailgunMerkletree(
    internal val wireName: String
) {
    UTXO("utxo"),
    TXID("txid"),
}

enum class RailgunScanStatus(
    internal val wireName: String
) {
    STARTED("Started"),
    UPDATED("Updated"),
    COMPLETE("Complete"),
    INCOMPLETE("Incomplete"),
}

sealed interface RailgunEvent {
    data class Scan(
        val tree: RailgunMerkletree,
        val status: RailgunScanStatus,
        val progress: Float,
    ) : RailgunEvent

    data class Log(
        val message: String
    ) : RailgunEvent

    /** How far the prover is, from 0 to 100. */
    data class Proof(
        val progress: Float,
        val status: String,
    ) : RailgunEvent

    /** The WebView's renderer died: the engine and wallet must be started and opened again. */
    data object Disconnected : RailgunEvent
}

class RailgunException(
    message: String
) : Exception(message)
