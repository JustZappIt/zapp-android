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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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

    /**
     * Confirmations accrued by the least-confirmed transaction of [address]'s recorded claim, or
     * null while that cannot be read.
     *
     * Null covers two states this wallet cannot tell apart, and the difference matters to whoever
     * renders it. The claim is built and broadcast by the *card's* isolated wallet, so this wallet
     * only learns of it when it mines and gets scanned — before that there is nothing to count, and
     * a claim that will never mine looks exactly the same. That is why the screen showing this
     * keeps a way to re-check rather than waiting on this flow alone.
     *
     * Read-only and confined to this wallet's own transactions: nothing here opens the card's
     * wallet or touches its bearer seed.
     */
    fun observeClaimConfirmations(address: String): Flow<Int?> =
        flow {
            val receipt =
                runCatching {
                    receivedGiftStorageProvider.getAll().firstOrNull { it.address == address }
                }.getOrNull()
            val claimTxids = receipt?.claimTxids.orEmpty()
            val accountIds = receipt?.let { candidateAccountIds(it) }.orEmpty()
            if (claimTxids.isEmpty() || accountIds.isEmpty()) {
                emit(null)
                return@flow
            }
            val synchronizer = synchronizerProvider.getSynchronizer()
            // Per transaction, the first account that has it. A `merge` here would interleave the
            // nulls every other account emits for a transaction it does not hold, and the count
            // would flicker between a real figure and "unknown".
            val minedHeights =
                combine(
                    claimTxids.map { txid ->
                        combine(
                            accountIds.map { transactionRepository.observeAccountTransaction(it, txid) }
                        ) { found -> found.firstNotNullOfOrNull { overview -> overview?.minedHeight?.value } }
                    }
                ) { heights -> heights.toList() }
            emitAll(
                combine(minedHeights, synchronizer.networkHeight) { heights, tip ->
                    val mined = heights.filterNotNull()
                    if (tip == null || mined.size < heights.size) {
                        // One transaction of the claim is still unmined, so the claim as a whole
                        // has no confirmations to report yet.
                        null
                    } else {
                        // The least-confirmed transaction is the one the wait is actually on.
                        mined.minOf { height -> (tip.value - height + 1).coerceAtLeast(0L) }.toInt()
                    }
                }
            )
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
        val payload = receipt?.claimLink

        if (receipt != null && payload != null) {
            val hasFinalDestination = receipt.isFinalized || hasFinalDestinationTransactions(receipt)
            if (hasFinalDestination) {
                val synchronizer = synchronizerProvider.getSynchronizer()
                val canSettle =
                    receipt.isFinalized ||
                        giftClaimDataSource
                            .inspectFinalization(
                                payload = payload,
                                cardAddress = receipt.address,
                                network = synchronizer.network,
                                endpoint = persistableWalletProvider.requirePersistableWallet().endpoint,
                            ).canSettle
                if (canSettle && !receipt.isFinalized) {
                    receivedGiftStorageProvider.markFinalized(receipt.address)
                }
                if (canSettle) {
                    giftClaimDataSource.cleanupFinalizedClaim(payload, receipt.address, synchronizer.network)
                    receivedGiftStorageProvider.settle(receipt.address)
                }
            }
        }
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
