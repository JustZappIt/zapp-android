// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.p2p.Usdc6

/** The Railgun note a payout is shielded to. The quote names its [commitment]; the relayer sends the rest. */
class PayoutNote(
    val npk: ByteArray,
    val encryptedBundle: List<ByteArray>,
    val shieldKey: ByteArray,
    val commitment: NoteCommitment,
)

class UserAcceptance(
    val userShare: SwapShare,
    val userProof: ByteArray,
    val viewingKeys: ByteArray,
)

/** The user's swap cryptography, keyed by swap index. */
interface AtomicSwapKeys {
    /** `Z`, the public share a quote is accepted with. */
    suspend fun userShare(index: Int): SwapShare

    /** The swap's own key, which signs for it and is never funded. */
    suspend fun authAddress(index: Int): Address

    suspend fun payoutNote(index: Int): PayoutNote

    /** Throws unless the maker's share proof verifies; proves ours in return, bound to the payout note. */
    suspend fun accept(
        index: Int,
        chainId: ChainId,
        contract: Address,
        quoteId: ByteArray,
        makerShare: SwapShare,
        makerProof: ByteArray,
    ): UserAcceptance

    /** The deposit address, from the maker share as the contract records it. */
    suspend fun depositAddress(
        index: Int,
        makerShare: SwapShare
    ): String

    /** `z`, which a claim reveals. */
    suspend fun claimSecret(index: Int): ByteArray

    suspend fun signPayout(
        index: Int,
        chainId: ChainId,
        contract: Address,
        swapId: SwapId,
        relayer: Address,
        fee: Usdc6,
    ): ByteArray
}

/** A deposit checked to fit in one transaction within its cap, not created yet. */
fun interface PreparedDeposit {
    /** Creates the transaction without sending it. */
    suspend fun create(): ZcashTransaction
}

/** The wallet's Zcash side of a forward swap. */
interface AtomicSwapZcash : SwapZcash {
    /** Checks that [zatoshi] to [address] fits in one transaction costing at most [maxTotalZat] in all. */
    suspend fun prepareDeposit(
        address: String,
        zatoshi: Long,
        maxTotalZat: Long?,
    ): PreparedDeposit

    /** A deposit to [address] the wallet created that isn't known to have expired, or null. */
    suspend fun findDeposit(address: String): ZcashTransaction?

    suspend fun depositStatus(deposit: ZcashTransaction): ZcashTransactionStatus

    /** The sweep home of a refunded deposit, or the one built before; null until the whole balance is spendable. */
    suspend fun prepareSweep(
        index: Int,
        makerShare: SwapShare,
        makerSecret: ByteArray,
        birthday: Long,
    ): ZcashTransaction?

    suspend fun sweepStatus(
        index: Int,
        makerShare: SwapShare,
        sweep: ZcashTransaction,
    ): ZcashTransactionStatus

    suspend fun forgetDepositAccount(
        index: Int,
        makerShare: SwapShare
    )
}

interface AtomicSwapStore {
    /** The next index of this store's count; swaps take theirs through [SwapIndices]. */
    suspend fun takeIndex(): Int

    suspend fun active(): AtomicSwapRecord?

    suspend fun save(record: AtomicSwapRecord)
}

/** Reads of the settlement chain a forward swap needs. */
interface AtomicSwapChainReader {
    /** Latest state, including swaps too recent to act on: used to avoid reusing keys or abandoning an open. */
    suspend fun swap(id: SwapId): SwapState?

    /**
     * The swap [terms] describe at the deployment's confirmation depth: what deposits and completion go by. One under
     * [id] that opened with other terms is [AtomicSwapBlock.MISMATCH].
     */
    suspend fun confirmedSwap(
        id: SwapId,
        terms: SwapTerms
    ): OnChainSwap?

    /**
     * The one consistent read: the swap [terms] describe and the newest block, the one as of the other. Every reveal
     * is judged on it, and judged again on a fresh one just before the share goes. A lock contract has none.
     */
    suspend fun swapNow(
        id: SwapId,
        terms: SwapTerms
    ): SwapRead

    /** The swap [terms] describe as of [block]: whether a ready shows that far below the head. */
    suspend fun swapAt(
        id: SwapId,
        terms: SwapTerms,
        block: Long,
    ): OnChainSwap?

    /** The confirmed payout event, searched from around [since] (unix seconds) through the confirmed head. */
    suspend fun confirmedPayout(
        id: SwapId,
        since: Long
    ): SwapPayoutEvidence?

    /** The time of the block whose confirmed `Refunded` event refunded swap [id], searched from around [since]. */
    suspend fun refundedAt(
        id: SwapId,
        since: Long
    ): Long?

    /** The latest block's time: the contract's clock. */
    suspend fun now(): Long

    /** Whether Railgun would take a shield of [token] now: not blocklisted, and not paused. */
    suspend fun railgunAccepts(token: Address): Boolean

    /** Whether [owner] opened a swap with [share] as its own: a reverse swap's user share is spent. */
    suspend fun makerKeyUsed(
        owner: Address,
        share: SwapShare
    ): Boolean

    /** The transaction that paid swap [id] out, looked for around [near] (unix seconds). */
    suspend fun payoutTx(
        id: SwapId,
        near: Long
    ): TxHash? = confirmedPayout(id, near)?.transaction
}

/** What the settlement contract actually paid, rather than what a relayer says it submitted. */
data class SwapPayoutEvidence(
    val transaction: TxHash,
    val relayer: Address,
    val fee: Usdc6,
)
