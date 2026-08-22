// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.bestEffort
import co.electriccoin.zcash.ui.common.datasource.GiftCardHoldings
import co.electriccoin.zcash.ui.common.datasource.GiftCardUnreachableException
import co.electriccoin.zcash.ui.common.datasource.GiftClaimDataSource
import co.electriccoin.zcash.ui.common.datasource.GiftClaimProgress
import co.electriccoin.zcash.ui.common.provider.GiftCardStorageProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.screen.gift.model.StoredGiftCard
import co.electriccoin.zcash.ui.screen.gift.model.toLinkPayload
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock

/** What a check found. Anything other than [COLLECTED] leaves the card's status untouched. */
enum class GiftCardCheckResult {
    /** The card's wallet is empty, so whoever held the link took the money. */
    COLLECTED,

    /** The funds are still sitting on the card. */
    WAITING,

    /** The card was never funded, so there is nothing to have been collected. */
    NOT_FUNDED,

    /**
     * The card's own wallet was scanned and its funding has never arrived, so nothing can have been
     * taken from it. The transaction is still in the mempool, or was dropped — and a dropped one can
     * still mine until it expires, which is exactly why this is not [COLLECTED].
     */
    FUNDING_PENDING,

    /** The card's server could not be reached, so the scan never started. */
    UNREACHABLE,

    /** The scan could not finish. Says nothing about the card either way. */
    UNKNOWN,
}

/**
 * Answers "did they open it?" for one minted card.
 *
 * There is no cheap way to ask. The funding note is shielded, so the only thing that can observe it
 * spent is a scan with the card's own viewing key — the same minutes-long sync a claim performs
 * (§11.1). That cost is why this is one card at a time on request, and never a background sweep
 * across the list.
 *
 * An empty wallet is not on its own an answer, and treating it as one is how a card gets settled
 * while its money is still on the way. The scan therefore reads the card wallet's own history
 * alongside its balance, and only the pair — this card's funding mined, balance now zero — reports
 * collected. That evidence comes from the card's wallet rather than the sender's records on
 * purpose: it stays correct on a device that has never seen the funding transaction, and it is the
 * same evidence whether the card was minted here or restored from somewhere else. The stored txid
 * is passed in to say *which* transaction in that history counts, not to replace it — see
 * [GiftCardHoldings.hasFundingArrived] for what any-mined-transaction cannot tell apart.
 */
class CheckGiftCardClaimedUseCase(
    private val synchronizerProvider: SynchronizerProvider,
    private val persistableWalletProvider: PersistableWalletProvider,
    private val giftClaimDataSource: GiftClaimDataSource,
    private val giftCardStorageProvider: GiftCardStorageProvider,
) {
    suspend operator fun invoke(
        card: StoredGiftCard,
        onProgress: (GiftClaimProgress) -> Unit,
    ): GiftCardCheckResult {
        // An empty wallet on a card that was never funded means nobody took anything — it was never
        // there. Only a funded card can be reported collected, and the txid that makes it funded is
        // the same one the scan needs, so it is taken here rather than re-read further down.
        val fundingTxid = card.fundingTxid ?: return GiftCardCheckResult.NOT_FUNDED

        return when (val outcome = inspect(card, fundingTxid, onProgress)) {
            is CheckOutcome.Failed -> {
                outcome.result
            }

            is CheckOutcome.Read -> {
                when {
                    outcome.holdings.isCollected -> {
                        markClaimed(card)
                        GiftCardCheckResult.COLLECTED
                    }

                    // Scanned to the tip and the funding is not there. Deliberately not recorded as
                    // a check: `lastCheckedAt` claims the card still held its funds, and this says
                    // the opposite — that they never reached it.
                    outcome.holdings.isEmpty -> {
                        GiftCardCheckResult.FUNDING_PENDING
                    }

                    else -> {
                        recordChecked(card)
                        GiftCardCheckResult.WAITING
                    }
                }
            }
        }
    }

    private sealed interface CheckOutcome {
        data class Read(
            val holdings: GiftCardHoldings,
        ) : CheckOutcome

        data class Failed(
            val result: GiftCardCheckResult,
        ) : CheckOutcome
    }

    private suspend fun inspect(
        card: StoredGiftCard,
        fundingTxid: String,
        onProgress: (GiftClaimProgress) -> Unit,
    ): CheckOutcome {
        val synchronizer = synchronizerProvider.getSynchronizer()
        val endpoint = persistableWalletProvider.requirePersistableWallet().endpoint
        return runCatching {
            CheckOutcome.Read(
                giftClaimDataSource.inspect(
                    payload = card.toLinkPayload(),
                    cardAddress = card.address,
                    network = synchronizer.network,
                    endpoint = endpoint,
                    fundingTxid = fundingTxid,
                    onProgress = onProgress,
                )
            )
        }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            Twig.error(throwable) { "Gift card ${card.id} could not be checked" }
            // Separated because the two need different copy: one is "you are offline", the other
            // is "something went wrong". Neither says anything about the card.
            if (throwable is GiftCardUnreachableException) {
                CheckOutcome.Failed(GiftCardCheckResult.UNREACHABLE)
            } else {
                CheckOutcome.Failed(GiftCardCheckResult.UNKNOWN)
            }
        }
    }

    /** Best effort for the same reason as [markClaimed]: it is a note about the past, not the card. */
    private suspend fun recordChecked(card: StoredGiftCard) {
        bestEffort("Gift card ${card.id} check time could not be recorded") {
            giftCardStorageProvider.recordChecked(id = card.id, at = Clock.System.now().toString())
        }
    }

    /**
     * Best effort: the card is collected whether or not the store takes the note, and a failure
     * here must not be reported back as "still waiting".
     */
    private suspend fun markClaimed(card: StoredGiftCard) {
        bestEffort("Gift card ${card.id} could not be marked claimed") {
            giftCardStorageProvider.markClaimed(id = card.id, at = Clock.System.now().toString())
        }
    }
}
