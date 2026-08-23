// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.datasource.GiftClaimDataSource
import co.electriccoin.zcash.ui.common.provider.GiftClaimOperationLock
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.ReceivedGiftStorageProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.screen.gift.model.ReceivedGift
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first

/**
 * Drops a received gift's retained link after SDK finality and isolated-wallet cleanup.
 */
class ConfirmGiftClaimUseCase(
    private val receivedGiftStorageProvider: ReceivedGiftStorageProvider,
    private val transactionRepository: TransactionRepository,
    private val synchronizerProvider: SynchronizerProvider,
    private val persistableWalletProvider: PersistableWalletProvider,
    private val giftClaimDataSource: GiftClaimDataSource,
    private val giftClaimOperationLock: GiftClaimOperationLock,
) {
    /**
     * Suspends until every transaction in [claimTxids] is final, then settles [address].
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
        receivedGiftStorageProvider.getAll().firstOrNull { it.address == address }?.let { finalize(it) }
    }

    /** Settles every receipt whose claim is already final. */
    suspend fun reconcile() {
        val unsettled = receivedGiftStorageProvider.getAll().filterNot { it.isSettled }
        if (unsettled.isEmpty()) return

        val finalized =
            transactionRepository
                .getTransactions()
                .filterIsInstance<ReceiveTransaction.Success>()
                .mapTo(mutableSetOf()) { it.id.txIdString() }

        unsettled
            .filter { it.claimTxids.isNotEmpty() && finalized.containsAll(it.claimTxids) }
            .forEach { finalize(it) }
    }

    private suspend fun finalize(receipt: ReceivedGift) {
        giftClaimOperationLock.withLock(receipt.address) { finalizeLocked(receipt) }
    }

    private suspend fun finalizeLocked(receipt: ReceivedGift) {
        val payload = receipt.claimLink ?: return
        val synchronizer = synchronizerProvider.getSynchronizer()
        if (!receipt.isFinalized) {
            val result =
                giftClaimDataSource.inspectFinalization(
                    payload = payload,
                    cardAddress = receipt.address,
                    network = synchronizer.network,
                    endpoint = persistableWalletProvider.requirePersistableWallet().endpoint,
                )
            if (!result.canSettle) return
            receivedGiftStorageProvider.markFinalized(receipt.address)
        }
        giftClaimDataSource.cleanupFinalizedClaim(payload, receipt.address, synchronizer.network)
        receivedGiftStorageProvider.settle(receipt.address)
    }
}
