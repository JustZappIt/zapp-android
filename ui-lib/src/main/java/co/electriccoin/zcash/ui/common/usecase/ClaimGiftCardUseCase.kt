// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.bestEffort
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.GiftClaimDataSource
import co.electriccoin.zcash.ui.common.datasource.GiftClaimOutcome
import co.electriccoin.zcash.ui.common.datasource.GiftClaimProgress
import co.electriccoin.zcash.ui.common.provider.GiftKeyProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.ReceivedGiftStorageProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.screen.gift.model.GiftBirthdayVerdict
import co.electriccoin.zcash.ui.screen.gift.model.GiftLinkCodec
import co.electriccoin.zcash.ui.screen.gift.model.GiftLinkError
import co.electriccoin.zcash.ui.screen.gift.model.GiftLinkException
import co.electriccoin.zcash.ui.screen.gift.model.GiftLinkPayload
import co.electriccoin.zcash.ui.screen.gift.model.ReceivedGift
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * A link checked as far as it can be without touching the network. [verdict] is what the recipient
 * still has to agree to — a long foreground scan is their decision, not something to spring on
 * them (§3.6).
 */
data class GiftClaimPreview(
    val payload: GiftLinkPayload,
    /** Derived from the link's mnemonic; the link itself does not carry it. */
    val cardAddress: String,
    val verdict: GiftBirthdayVerdict,
)

/**
 * The wallet does not yet know the chain tip, so how far back this card sits cannot be judged.
 *
 * Distinct from [GiftLinkError.BIRTHDAY_ABOVE_TIP] on purpose: that means the *card* is wrong, this
 * means *we* are not ready. Telling a recipient their good card claims to be from the future, with
 * no way to retry, is how a real gift comes to look fake.
 */
class GiftClaimNotReadyException : RuntimeException("Chain tip unknown")

/**
 * Turns a gift link into money in this wallet.
 *
 * Split so that everything checkable offline happens in [preview], before a single block is
 * downloaded — a tampered or wrong-network link must never get as far as starting a scan, and the
 * recipient must be able to see what a card is worth before deciding whether to scan for it.
 */
class ClaimGiftCardUseCase(
    private val accountDataSource: AccountDataSource,
    private val synchronizerProvider: SynchronizerProvider,
    private val persistableWalletProvider: PersistableWalletProvider,
    private val giftKeyProvider: GiftKeyProvider,
    private val giftClaimDataSource: GiftClaimDataSource,
    private val receivedGiftStorageProvider: ReceivedGiftStorageProvider,
) {
    /**
     * Parses and validates [uri] and decides what claiming it would cost.
     *
     * @throws GiftLinkException with the specific check that failed.
     */
    suspend fun preview(uri: String): GiftClaimPreview {
        val synchronizer = synchronizerProvider.getSynchronizer()

        // Wrong network, bad version, unparseable amount, over-long message — all offline.
        val payload = GiftLinkCodec.decode(uri, synchronizer.network)

        // The card's address, which identifies it everywhere below: the isolated wallet's alias
        // and the receipt. Derived rather than carried, so there is nothing to disagree with.
        val cardAddress = giftKeyProvider.deriveAddress(payload.mnemonic, synchronizer.network)

        // Wait rather than fail. On the cold start an App Link produces, networkHeight is null for
        // a second or two, and reading `.value` once would reject every link opened from a chat.
        val tip =
            withTimeoutOrNull(TIP_TIMEOUT) {
                synchronizer.networkHeight
                    .filterNotNull()
                    .first()
                    .value
            } ?: throw GiftClaimNotReadyException()

        return GiftClaimPreview(
            payload = payload,
            cardAddress = cardAddress,
            verdict = GiftLinkCodec.evaluateBirthday(payload.birthdayHeight, tip),
        )
    }

    /**
     * Syncs the card's own wallet and moves its funds here.
     *
     * Claims **exactly** the card amount and never sweeps. The ephemeral address is plaintext in
     * the link, so anyone holding it can send dust there; targeting the amount means the note
     * selector takes the one funding note and ignores the dust. A genuine top-up above the card
     * amount is left behind — predictable, and the accepted trade. Do not "optimise" this into a
     * sweep.
     */
    suspend operator fun invoke(
        payload: GiftLinkPayload,
        cardAddress: String,
        onProgress: (GiftClaimProgress) -> Unit,
    ): GiftClaimOutcome {
        val synchronizer = synchronizerProvider.getSynchronizer()
        val recipient =
            accountDataSource
                .getSelectedAccount()
                .unified.address.address
        // The wallet's own endpoint rather than the bundled default, so a claim talks to whatever
        // server the user chose for everything else.
        val endpoint = persistableWalletProvider.requirePersistableWallet().endpoint

        val outcome =
            giftClaimDataSource.claim(
                payload = payload,
                cardAddress = cardAddress,
                network = synchronizer.network,
                endpoint = endpoint,
                recipientAddress = recipient,
                onProgress = onProgress,
            )

        // An empty card this wallet has already collected is "you have it", never "somebody else
        // took it" — and the two are the same scan, so only the receipt can tell them apart. This
        // is what the [NonCancellable] write below is for: a claim can succeed and still lose its
        // outcome on the way back to the screen if the app was backgrounded while the broadcast
        // ran, and the recipient's next attempt would otherwise be told their gift is gone.
        if (outcome is GiftClaimOutcome.Empty) collectedEarlier(payload, cardAddress)?.let { return it }

        // NonCancellable for the same reason `GiftClaimDataSource` deletes under it: the money has
        // already moved by the time this runs, and a cancelled scope would drop the only durable
        // record that it did.
        if (outcome is GiftClaimOutcome.Claimed) {
            withContext(NonCancellable) { recordReceipt(payload, cardAddress, outcome) }
        }
        return outcome
    }

    /**
     * The receipt for this card, rebuilt as the outcome it came from, or null if there is none.
     * Keyed on the address, which is the card's identity.
     *
     * Reads wrong only for an address funded a second time and emptied again, reporting the first
     * collection rather than the second. Nothing in the app can re-fund a spent card, and that
     * answer is still closer to true than "nothing left on this card".
     */
    private suspend fun collectedEarlier(payload: GiftLinkPayload, cardAddress: String): GiftClaimOutcome.Claimed? =
        runCatching {
            receivedGiftStorageProvider
                .getAll()
                .firstOrNull { it.address == cardAddress && it.network == payload.network }
                ?.let { GiftClaimOutcome.Claimed(amount = Zatoshi(it.amountZatoshi), txIds = it.claimTxids) }
        }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            // Falls through to Empty, which is what this path already said before the receipt
            // existed. A store that will not read must not turn a claim into a crash.
            Twig.warn { "Received gift receipts could not be read" }
            null
        }

    /**
     * Keeps the link, which is what makes a claim retryable: the broadcast reached the mempool but
     * can still expire unmined, and by now the card's own wallet is gone. `ConfirmGiftClaimUseCase`
     * drops it once the claim is on chain.
     *
     * Best effort all the same — the money has moved either way, and failing here costs the retry
     * rather than the gift.
     */
    private suspend fun recordReceipt(
        payload: GiftLinkPayload,
        cardAddress: String,
        outcome: GiftClaimOutcome.Claimed,
    ) {
        bestEffort("Claimed gift could not be recorded") {
            receivedGiftStorageProvider.record(
                ReceivedGift(
                    address = cardAddress,
                    network = payload.network,
                    amountZatoshi = outcome.amount.value,
                    claimedAt = Clock.System.now().toString(),
                    claimTxids = outcome.txIds,
                    message = payload.message,
                    claimLink = payload,
                )
            )
        }
    }

    private companion object {
        /** Long enough for a cold-start connection, short enough not to look frozen. */
        val TIP_TIMEOUT = 30.seconds
    }
}
