// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import xyz.justzappit.evm.rpc.TransactionStatus
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.p2p.Usdc6

/** The signatures only a reverse swap's own key makes. */
interface ReverseSwapKeys {
    suspend fun signOpen(record: ReverseSwapRecord): ByteArray

    suspend fun signReady(
        record: ReverseSwapRecord,
        deadline: Long
    ): ByteArray

    suspend fun signLockRefund(
        record: ReverseSwapRecord,
        deadline: Long
    ): ByteArray

    suspend fun signPayout(
        record: ReverseSwapRecord,
        terms: RelayerTerms
    ): ByteArray

    suspend fun signRescue(
        record: ReverseSwapRecord,
        terms: RelayerTerms,
        nonce: Long,
        deadline: Long,
    ): ByteArray
}

/** Reads of the settlement chain a reverse swap needs, [ReverseSwapChain.read] far enough behind the head. */
interface ReverseSwapChain {
    suspend fun read(id: SwapId): ReverseChainState

    /** The funding transaction as the node sees it, confirmed once its escrow would count. */
    suspend fun fundingStatus(transaction: TxHash): TransactionStatus

    /** What a refund Railgun sent back holds in the swap's vault. */
    suspend fun vaultBalance(id: SwapId): Usdc6

    /** Null for a legacy deployment that cannot consume rescue authorizations. */
    suspend fun rescueNonce(id: SwapId): Long?
}

/** Funding a reverse swap's escrow from the private balance. */
interface ReverseSwapFunding {
    suspend fun cost(escrow: Usdc6): ReverseFundingCost

    suspend fun prepare(
        record: ReverseSwapRecord,
        signature: ByteArray
    ): ReverseFundingTransaction

    suspend fun submit(transaction: ReverseFundingTransaction)
}

/** What both directions ask of the Zcash wallet. */
interface SwapZcash {
    suspend fun chainHeight(): Long

    suspend fun submit(transaction: ZcashTransaction)
}

/** The wallet's Zcash side of a reverse swap: the joint account the maker pays, and the sweep home. */
interface ReverseSwapZcash : SwapZcash {
    /** Imports the joint account from the swap's birthday, unless the wallet watches it already. */
    suspend fun importAccount(record: ReverseSwapRecord): JointAccountId

    /**
     * What the joint account received in transactions with [confirmations] or more, counted against the tip the
     * wallet synced to, and the most a sweep of those notes home pays. Not yet spendable, as the wallet counts it.
     */
    suspend fun estimateReceive(
        record: ReverseSwapRecord,
        confirmations: Int,
    ): ReverseReceiveEstimate

    /** The sweep home, or the one built before an interruption; never sent. Null until every note is spendable. */
    suspend fun prepareReceive(
        record: ReverseSwapRecord,
        makerSecret: ByteArray
    ): ReverseReceiveTransaction?

    suspend fun receiveStatus(
        record: ReverseSwapRecord,
        receive: ReverseReceiveTransaction
    ): ZcashTransactionStatus

    /** Stops watching the swap's joint account, if it was imported: nothing more comes from it. */
    suspend fun forget(record: ReverseSwapRecord)
}

interface ReverseSwapStore {
    suspend fun active(): ReverseSwapRecord?

    /** The conversion kept under [index], the active one or one before it. */
    suspend fun find(index: Int): ReverseSwapRecord?

    suspend fun save(record: ReverseSwapRecord)

    /** Replaces the conversion kept under [record]'s index, leaving which one is active alone. */
    suspend fun update(record: ReverseSwapRecord)
}

/** Saves [updated] in [record]'s place, unless nothing changed. */
internal suspend fun ReverseSwapStore.keep(
    record: ReverseSwapRecord,
    updated: ReverseSwapRecord,
) {
    if (updated != record) save(updated)
}
