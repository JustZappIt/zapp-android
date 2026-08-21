// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.GiftCardStorageProvider
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.screen.gift.model.GiftCardStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlin.time.Clock

/**
 * Advances a submitted card from draft to funded, once its funding transaction has mined.
 *
 * Submission alone is not funding: a transaction in the mempool can still be dropped, and a card
 * recorded as funded with nothing behind it is a card the sender believes exists and the recipient
 * cannot claim. Until this runs, the card stays a draft carrying its txid — which is already enough
 * for the sender to share it, and already counts as unshared funds against account deletion.
 */
class ConfirmGiftCardFundingUseCase(
    private val giftCardStorageProvider: GiftCardStorageProvider,
    private val transactionRepository: TransactionRepository,
) {
    /**
     * Suspends until [fundingTxid] appears with a block behind it, then marks [cardId] funded.
     *
     * Cancelling this loses nothing: [reconcile] picks the card up on the next pass.
     */
    suspend operator fun invoke(cardId: String, fundingTxid: String) {
        // Success is the repository's name for "mined" — TransactionRepository maps a non-null
        // minedHeight to Confirmed and Confirmed to Success. Pending and Failed both keep waiting.
        transactionRepository
            .observeTransaction(fundingTxid)
            .filterIsInstance<SendTransaction.Success>()
            .first()
        markFunded(cardId, fundingTxid)
    }

    /**
     * Sweeps every submitted-but-unconfirmed card against transactions already on chain.
     *
     * This is what recovers a card whose [invoke] never got to finish, because the process died or
     * the sender left the screen between broadcast and the next block.
     */
    suspend fun reconcile() {
        val pending =
            giftCardStorageProvider.getAll().filter {
                it.status == GiftCardStatus.DRAFT && it.fundingTxid != null
            }
        if (pending.isEmpty()) return

        val minedTxIds =
            transactionRepository
                .getTransactions()
                .filterIsInstance<SendTransaction.Success>()
                .mapTo(mutableSetOf()) { it.id.txIdString() }

        pending.forEach { card ->
            val txid = card.fundingTxid ?: return@forEach
            if (txid in minedTxIds) markFunded(card.id, txid)
        }
    }

    private suspend fun markFunded(cardId: String, fundingTxid: String) {
        // A losing race with another writer is a legitimate outcome, not a failure: the ledger
        // advances by maximum, so whichever call lands second is a no-op on an already-funded card.
        runCatching {
            giftCardStorageProvider.markFunded(
                id = cardId,
                fundingTxid = fundingTxid,
                at = Clock.System.now().toString(),
            )
        }.onFailure { throwable ->
            if (throwable is CancellationException) throw throwable
            Twig.warn { "Gift card $cardId could not be marked funded" }
        }
    }
}
