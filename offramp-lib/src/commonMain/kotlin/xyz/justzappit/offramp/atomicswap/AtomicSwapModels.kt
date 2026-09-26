// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import kotlinx.serialization.Serializable
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.types.Address

/**
 * A ZecSwap deployment that pays into Railgun: the maker quoting it, the relayer sending the user's
 * transactions, and the contract they settle on.
 */
data class AtomicSwapConfig(
    val makerUrl: String,
    val relayerUrl: String,
    val chainId: Long,
    val contract: Address,
    val token: Address,
    val railgunProxy: Address,
    /** The most a relayer may keep from a payout, in token base units. */
    val maxRelayerFee: BigInteger,
    /**
     * The soonest `t0` a swap may have: time for the deposit to get the confirmations its maker
     * waits for. Until `t0` an unresponsive maker holds the deposit, so the latest is two hours.
     */
    val minSecondsToT0: Long = TEN_CONFIRMATIONS_AND_MARGIN_SECONDS,
)

// Ten Zcash confirmations take about 12.5 minutes; this leaves room for them and a margin.
private const val TEN_CONFIRMATIONS_AND_MARGIN_SECONDS = 25 * 60L

enum class SwapStage { OPEN, READY, CLAIMED, REFUNDED }

/** `getSwap(id)` as the contract returns it. Shares are `x ‖ y`, 64 bytes. */
class OnChainSwap(
    val maker: Address,
    val t0: Long,
    val stage: SwapStage,
    /** A payout into Railgun has left the contract. */
    val paidOut: Boolean,
    val user: Address,
    val t1: Long,
    val token: Address,
    val claimLockUntil: Long,
    val amount: BigInteger,
    val refundLockUntil: Long,
    val makerShare: ByteArray,
    val userShare: ByteArray,
    /** The share a claim or refund revealed, once there has been one. */
    val secret: ByteArray,
    val payoutNote: ByteArray,
)

/** What the app keeps about a swap it accepted. Its keys derive from the seed and [index] again. */
@Serializable
data class AtomicSwapRecord(
    val index: Int,
    val quote: SwapQuote,
    val swapId: String,
    /** The Zcash height read before depositing: a refund imports the deposit account from here. */
    val zcashHeight: Long,
    /** Set before the deposit is created, so an interrupted deposit is never paid twice. */
    val depositAttempted: Boolean = false,
    val depositTxId: String? = null,
    val outcome: String? = null,
) {
    val finished: Boolean get() = outcome != null
}

sealed interface AtomicSwapStep {
    data class Waiting(
        val reason: String
    ) : AtomicSwapStep

    data class Finished(
        val outcome: String
    ) : AtomicSwapStep
}

/** `POST /v1/quote`'s answer. Addresses, ids, shares and proofs are `0x` hex; `amount` is decimal. */
@Serializable
data class SwapQuote(
    val quoteId: String,
    val maker: String,
    val makerShare: String,
    val makerProof: String,
    val chainId: Long,
    val contract: String,
    val token: String,
    val amount: String,
    val depositZat: Long,
    val expiresAt: Long,
)

@Serializable
internal data class QuoteRequest(
    val units: Int,
    val payout: String,
    val payoutNote: String,
)

@Serializable
internal data class AcceptRequest(
    val userShare: String,
    val userProof: String,
    val viewingKeys: String,
)

@Serializable
internal data class Accepted(
    val swapId: String
)

@Serializable
internal data class RelayerTerms(
    val relayer: String,
    val chainId: Long,
    val contract: String,
    val fee: String,
)

@Serializable
internal data class LockClaimRequest(
    val swapId: String,
    val deadline: Long,
    val signature: String,
)

@Serializable
internal data class ClaimRequest(
    val swapId: String,
    val secret: String,
    val payout: PayoutRequest,
)

@Serializable
internal data class PayoutRequest(
    val swapId: String,
    val note: NoteJson,
    val fee: String,
    val signature: String,
)

@Serializable
internal data class NoteJson(
    val npk: String,
    val encryptedBundle: List<String>,
    val shieldKey: String,
)

@Serializable
internal data class Sent(
    val transactions: List<String>
)
