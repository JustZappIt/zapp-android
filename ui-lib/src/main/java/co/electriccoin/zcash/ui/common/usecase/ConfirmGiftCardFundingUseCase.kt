// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionState
import co.electriccoin.zcash.ui.common.bestEffort
import co.electriccoin.zcash.ui.common.provider.GiftCardStorageProvider
import co.electriccoin.zcash.ui.common.provider.GiftFundingOperationLock
import co.electriccoin.zcash.ui.common.repository.SyncedAccountTransactionSnapshot
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.screen.gift.model.GiftFundingLifecycle
import co.electriccoin.zcash.ui.screen.gift.model.StoredGiftCard
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlin.time.Clock

/**
 * Reconciles each locally-created card with the main wallet's authoritative transaction database.
 *
 * A retry is enabled only from terminal evidence: either the fully-synced database contains no
 * transaction created after the durable start marker, or every candidate belonging to the attempt
 * is [TransactionState.Expired]. Pending and temporarily unavailable data remain unresolved.
 */
@Suppress("TooManyFunctions")
class ConfirmGiftCardFundingUseCase(
    private val giftCardStorageProvider: GiftCardStorageProvider,
    private val transactionRepository: TransactionRepository,
    private val giftFundingOperationLock: GiftFundingOperationLock,
) {
    /** Waits for one known funding transaction to become either confirmed or safely retryable. */
    suspend operator fun invoke(cardId: String, fundingTxid: String) {
        val card = giftCardStorageProvider.get(cardId) ?: return
        val terminal =
            transactionRepository
                .observeAccountTransaction(card.sourceAccountUuid, fundingTxid)
                .filterNotNull()
                .first {
                    it.isSentTransaction &&
                        it.transactionState != TransactionState.Pending
                }

        giftFundingOperationLock.withLock(cardId) {
            val current = giftCardStorageProvider.get(cardId) ?: return@withLock
            if (!current.needsFundingReconciliation() || current.fundingTxid != fundingTxid) {
                return@withLock
            }
            when (terminal.transactionState) {
                TransactionState.Confirmed -> {
                    markFunded(cardId, fundingTxid)
                }

                TransactionState.Expired -> {
                    // Observe may emit during startup. Re-read from the fully-synced snapshot before
                    // allowing another spend; `Expired` there is the SDK's public terminal state.
                    val snapshot =
                        transactionRepository.getSyncedAccountTransactionSnapshot(current.sourceAccountUuid)
                    reconcileKnownTransaction(current, fundingTxid, snapshot)
                }

                TransactionState.Pending -> {
                    Unit
                }
            }
        }
    }

    /**
     * Recovers every active attempt after process death.
     *
     * The per-card operation lock is shared with [FundGiftCardUseCase]. It prevents a synced empty
     * snapshot from clearing the durable marker while transaction creation is still running in this
     * process. After process death there is no creator left, so a synced absence is conclusive.
     */
    internal suspend fun reconcile(): List<GiftFundingWatch> {
        val watches = linkedSetOf<GiftFundingWatch>()
        giftCardStorageProvider
            .getAll()
            .filter { it.needsFundingReconciliation() }
            .forEach { snapshot ->
                val watch =
                    giftFundingOperationLock.withLock(snapshot.id) {
                        val card = giftCardStorageProvider.get(snapshot.id) ?: return@withLock null
                        if (!card.needsFundingReconciliation()) return@withLock null
                        val transactions =
                            transactionRepository.getSyncedAccountTransactionSnapshot(card.sourceAccountUuid)
                        reconcile(card, transactions)
                        giftCardStorageProvider
                            .get(card.id)
                            ?.takeIf { it.needsFundingReconciliation() }
                            ?.fundingTxid
                            ?.let { GiftFundingWatch(card.id, it) }
                    }
                watch?.let(watches::add)
            }
        return watches.toList()
    }

    /**
     * Reconciles startup state, then keeps every recovered transaction observed in this caller's
     * scope. Without this second phase, a transaction first seen Pending would remain stuck until
     * the screen was recreated even after it later confirmed or expired.
     */
    suspend fun reconcileAndObserve() =
        supervisorScope {
            reconcile().forEach { watch ->
                launch {
                    bestEffort("Gift card ${watch.cardId} funding observation stopped") {
                        invoke(watch.cardId, watch.fundingTxid)
                    }
                }
            }
        }

    private suspend fun reconcile(
        card: StoredGiftCard,
        snapshot: SyncedAccountTransactionSnapshot,
    ) {
        val txid = card.fundingTxid
        if (txid == null) {
            reconcileAttemptWithoutTxid(card, snapshot)
        } else {
            reconcileKnownTransaction(card, txid, snapshot)
        }
    }

    private suspend fun reconcileAttemptWithoutTxid(
        card: StoredGiftCard,
        snapshot: SyncedAccountTransactionSnapshot,
    ) {
        val historical = card.fundingFailures.mapNotNullTo(mutableSetOf()) { it.transactionId }
        val candidates = snapshot.sendsTo(card.address).filterNot { it.txId.txIdString() in historical }
        val live = candidates.filter { it.transactionState != TransactionState.Expired }
        // One proposal produces one transaction. More than one live candidate is corrupted or
        // externally-created evidence; choosing either could hide a second spend, so fail closed.
        if (live.size > 1) return
        live.singleOrNull()?.let { transaction ->
            val txid = transaction.txId.txIdString()
            attach(card.id, txid)
            if (transaction.transactionState == TransactionState.Confirmed) markFunded(card.id, txid)
            return
        }

        val expiredCandidates = candidates.withState(TransactionState.Expired)
        val expired = expiredCandidates.mapTo(mutableSetOf()) { it.txId.txIdString() }
        if (expired.isNotEmpty()) {
            markExpired(card.id, expired)
        } else {
            markNotCreated(card.id)
        }
    }

    private suspend fun reconcileKnownTransaction(
        card: StoredGiftCard,
        txid: String,
        snapshot: SyncedAccountTransactionSnapshot,
    ) {
        val current =
            snapshot.transactions.firstOrNull { it.txId.txIdString() == txid && it.isSentTransaction }
                ?: return
        when (current.transactionState) {
            TransactionState.Confirmed -> markFunded(card.id, txid)
            TransactionState.Pending -> Unit
            TransactionState.Expired -> reconcileExpiredCurrent(card, txid, snapshot)
        }
    }

    private suspend fun reconcileExpiredCurrent(
        card: StoredGiftCard,
        currentTxid: String,
        snapshot: SyncedAccountTransactionSnapshot,
    ) {
        val historical = card.fundingFailures.mapNotNullTo(mutableSetOf()) { it.transactionId }
        val otherCandidates =
            snapshot
                .sendsTo(card.address)
                .filterNot { it.txId.txIdString() == currentTxid || it.txId.txIdString() in historical }
        val live = otherCandidates.filter { it.transactionState != TransactionState.Expired }
        if (live.size > 1) return
        val expiredCandidates = otherCandidates.withState(TransactionState.Expired)
        val expired =
            expiredCandidates
                .mapTo(mutableSetOf(currentTxid)) { it.txId.txIdString() }
        val replacement = live.singleOrNull()

        if (replacement == null) {
            markExpired(card.id, expired)
            return
        }

        val replacementTxid = replacement.txId.txIdString()
        bestEffort("Gift card ${card.id} funding transaction could not be replaced") {
            giftCardStorageProvider.replaceExpiredFunding(
                id = card.id,
                expiredFundingTxids = expired,
                activeFundingTxid = replacementTxid,
                at = Clock.System.now().toString(),
            )
        }
        if (replacement.transactionState == TransactionState.Confirmed) {
            markFunded(card.id, replacementTxid)
        }
    }

    private suspend fun attach(cardId: String, fundingTxid: String) {
        bestEffort("Gift card $cardId funding could not be reattached") {
            giftCardStorageProvider.recordFundingCreated(
                id = cardId,
                fundingTxid = fundingTxid,
                at = Clock.System.now().toString(),
            )
        }
    }

    private suspend fun markNotCreated(cardId: String) {
        bestEffort("Gift card $cardId missing funding transaction could not be resolved") {
            giftCardStorageProvider.markFundingNotCreated(cardId, Clock.System.now().toString())
        }
    }

    private suspend fun markExpired(cardId: String, fundingTxids: Set<String>) {
        bestEffort("Gift card $cardId expired funding could not be resolved") {
            giftCardStorageProvider.markFundingExpired(
                id = cardId,
                fundingTxids = fundingTxids,
                at = Clock.System.now().toString(),
            )
        }
    }

    private suspend fun markFunded(cardId: String, fundingTxid: String) {
        bestEffort("Gift card $cardId could not be marked funded") {
            giftCardStorageProvider.markFunded(
                id = cardId,
                fundingTxid = fundingTxid,
                at = Clock.System.now().toString(),
            )
        }
    }
}

internal data class GiftFundingWatch(
    val cardId: String,
    val fundingTxid: String,
)

private fun StoredGiftCard.needsFundingReconciliation() =
    !isFundingMined &&
        when (fundingLifecycle) {
            is GiftFundingLifecycle.Attempting,
            is GiftFundingLifecycle.Created,
            is GiftFundingLifecycle.Submitted,
            -> true

            GiftFundingLifecycle.NeverStarted,
            is GiftFundingLifecycle.Retryable,
            is GiftFundingLifecycle.Mined,
            -> false
        }

private fun List<TransactionOverview>.withState(state: TransactionState) = filter { it.transactionState == state }
