// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.TransactionState
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.GiftClaimDataSource
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.provider.GiftClaimOperationLock
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.ReceivedGiftStorageProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.screen.gift.model.ReceivedGift
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge

/**
 * Drops a received gift's retained link after SDK finality and isolated-wallet cleanup.
 */
class ConfirmGiftClaimUseCase(
    private val receivedGiftStorageProvider: ReceivedGiftStorageProvider,
    private val transactionRepository: TransactionRepository,
    private val accountDataSource: AccountDataSource,
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
        val receipt =
            receivedGiftStorageProvider
                .getAll()
                .firstOrNull { claimTxids.isNotEmpty() && it.address == address }
        if (receipt != null) awaitFinality(receipt, claimTxids)
    }

    private suspend fun awaitFinality(receipt: ReceivedGift, claimTxids: List<String>) {
        val accountIds = candidateAccountIds(receipt)
        if (accountIds.isEmpty()) return
        // A claim arrives here as an ordinary incoming transaction, not a send.
        claimTxids.forEach { txid ->
            accountIds
                .map { transactionRepository.observeAccountTransaction(it, txid) }
                .merge()
                .filterNotNull()
                .first { !it.isSentTransaction && it.transactionState == TransactionState.Confirmed }
        }
        finalize(receipt)
    }

    /** Settles every receipt whose claim is already final. */
    suspend fun reconcile() {
        val unsettled = receivedGiftStorageProvider.getAll().filterNot { it.isSettled }
        if (unsettled.isEmpty()) return

        val allAccountIds = allAccountIds()
        val transactionsByAccount =
            allAccountIds.associateWith { accountId ->
                transactionRepository
                    .getAccountTransactions(accountId)
                    .filter { !it.isSentTransaction && it.transactionState == TransactionState.Confirmed }
                    .mapTo(mutableSetOf()) { it.txId.txIdString() }
            }

        unsettled
            .filter { receipt ->
                receipt.claimTxids.isNotEmpty() &&
                    candidateAccountIds(receipt, allAccountIds).any { accountId ->
                        transactionsByAccount[accountId].orEmpty().containsAll(receipt.claimTxids)
                    }
            }.forEach { finalize(it) }
    }

    private suspend fun candidateAccountIds(receipt: ReceivedGift): List<String> =
        candidateAccountIds(receipt, allAccountIds())

    private fun candidateAccountIds(receipt: ReceivedGift, allAccountIds: List<String>): List<String> =
        receipt.destinationAccountUuid
            ?.takeIf(allAccountIds::contains)
            ?.let(::listOf)
            // A re-imported account can have a new SDK UUID. Falling back to every current account
            // keeps the persisted destination hint useful without making it an orphaning key.
            ?: allAccountIds

    private suspend fun allAccountIds(): List<String> =
        accountDataSource
            .getAllAccounts()
            .map { it.sdkAccount.accountUuid.toStorageKeyId() }

    private suspend fun finalize(receipt: ReceivedGift) {
        giftClaimOperationLock.withLock(receipt.address) { finalizeLocked(receipt) }
    }

    private suspend fun finalizeLocked(snapshot: ReceivedGift) {
        // Reconcile and a foreground retry read independently. The retry can attach a replacement
        // txid while reconcile waits for the lock, so finalizing its stale snapshot can discard the
        // only retry secret before the replacement is final.
        val receipt =
            receivedGiftStorageProvider
                .getAll()
                .firstOrNull { it.address == snapshot.address && !it.isSettled }
                ?: return
        val payload = receipt.claimLink ?: return
        if (!receipt.isFinalized && !hasFinalDestinationTransactions(receipt)) return
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

    private suspend fun hasFinalDestinationTransactions(receipt: ReceivedGift): Boolean {
        if (receipt.claimTxids.isEmpty()) return false
        val accountIds = candidateAccountIds(receipt)
        return accountIds.any { accountId ->
            transactionRepository
                .getAccountTransactions(accountId)
                .asSequence()
                .filter { !it.isSentTransaction && it.transactionState == TransactionState.Confirmed }
                .map { it.txId.txIdString() }
                .toSet()
                .containsAll(receipt.claimTxids)
        }
    }
}
