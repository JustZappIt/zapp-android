// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.bestEffort
import co.electriccoin.zcash.ui.common.provider.ReceivedGiftStorageProvider
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first

/**
 * Drops a received gift's retained link once its claim has mined.
 *
 * The mirror of [ConfirmGiftCardFundingUseCase] on the recipient's side, and the same distinction:
 * a broadcast that reached the mempool is not one that landed. Until it has, the card still holds
 * its funds and the link is the only thing left that can move them, so it is kept — and dropped
 * here, on evidence, because a retained bearer secret that is no longer needed is pure exposure.
 */
class ConfirmGiftClaimUseCase(
    private val receivedGiftStorageProvider: ReceivedGiftStorageProvider,
    private val transactionRepository: TransactionRepository,
) {
    /**
     * Suspends until every transaction in [claimTxids] is mined, then settles [address].
     *
     * Cancelling loses nothing: [reconcile] picks the receipt up on the next pass.
     */
    suspend operator fun invoke(address: String, claimTxids: List<String>) {
        if (claimTxids.isEmpty()) return
        // A claim arrives here as an ordinary incoming transaction, so Receive rather than Send.
        claimTxids.forEach { txid ->
            transactionRepository
                .observeTransaction(txid)
                .filterIsInstance<ReceiveTransaction.Success>()
                .first()
        }
        settle(address)
    }

    /** Settles every receipt still holding a link whose claim is already on chain. */
    suspend fun reconcile() {
        val unsettled =
            runCatching { receivedGiftStorageProvider.getAll().filterNot { it.isSettled } }
                .getOrDefault(emptyList())
        if (unsettled.isEmpty()) return

        val mined =
            transactionRepository
                .getTransactions()
                .filterIsInstance<ReceiveTransaction.Success>()
                .mapTo(mutableSetOf()) { it.id.txIdString() }

        unsettled
            .filter { it.claimTxids.isNotEmpty() && mined.containsAll(it.claimTxids) }
            .forEach { settle(it.address) }
    }

    private suspend fun settle(address: String) {
        bestEffort("Received gift claim could not be settled") {
            receivedGiftStorageProvider.settle(address)
        }
    }
}
