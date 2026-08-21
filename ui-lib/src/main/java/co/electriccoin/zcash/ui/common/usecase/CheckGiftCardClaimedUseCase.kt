// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.datasource.GiftCardHoldings
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
        // there. Only a funded card can be reported collected.
        if (card.fundingTxid == null) return GiftCardCheckResult.NOT_FUNDED

        val holdings = inspect(card, onProgress)
        return when {
            holdings == null -> {
                GiftCardCheckResult.UNKNOWN
            }

            !holdings.isEmpty -> {
                GiftCardCheckResult.WAITING
            }

            else -> {
                markClaimed(card)
                GiftCardCheckResult.COLLECTED
            }
        }
    }

    private suspend fun inspect(card: StoredGiftCard, onProgress: (GiftClaimProgress) -> Unit): GiftCardHoldings? {
        val synchronizer = synchronizerProvider.getSynchronizer()
        val endpoint = persistableWalletProvider.requirePersistableWallet().endpoint
        return runCatching {
            giftClaimDataSource.inspect(
                payload = card.toLinkPayload(),
                network = synchronizer.network,
                endpoint = endpoint,
                onProgress = onProgress,
            )
        }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            Twig.error(throwable) { "Gift card ${card.id} could not be checked" }
            null
        }
    }

    /**
     * Best effort: the card is collected whether or not the store takes the note, and a failure
     * here must not be reported back as "still waiting".
     */
    private suspend fun markClaimed(card: StoredGiftCard) {
        runCatching { giftCardStorageProvider.markClaimed(id = card.id, at = Clock.System.now().toString()) }
            .onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                Twig.warn { "Gift card ${card.id} could not be marked claimed" }
            }
    }
}
