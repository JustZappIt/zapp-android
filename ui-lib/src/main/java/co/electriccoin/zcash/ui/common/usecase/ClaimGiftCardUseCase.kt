// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.GiftClaimDataSource
import co.electriccoin.zcash.ui.common.datasource.GiftClaimOutcome
import co.electriccoin.zcash.ui.common.datasource.GiftClaimProgress
import co.electriccoin.zcash.ui.common.provider.GiftClaimOperationLock
import co.electriccoin.zcash.ui.common.provider.GiftKeyProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.ReceivedGiftStorageProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.ZcashNetworkProvider
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
 * A link checked as far as it can be without touching the network — which is everything the card
 * says about itself. What it costs to claim needs the chain tip, so that is [birthdayVerdict],
 * asked for separately: a recipient must see what they were sent while the wallet is still finding
 * the chain, not a spinner.
 */
data class GiftClaimPreview(
    val payload: GiftLinkPayload,
    /** Derived from the link's mnemonic; the link itself does not carry it. */
    val cardAddress: String,
    /** False when this device has no wallet yet, so there is nowhere to claim into. */
    val hasWallet: Boolean,
    /**
     * Set when this wallet's own receipt says it already holds this card's funds, which is the
     * answer to a link opened twice — and one no amount of scanning can improve on.
     */
    val collected: GiftClaimOutcome.Claimed? = null,
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
    private val zcashNetworkProvider: ZcashNetworkProvider,
    private val receivedGiftStorageProvider: ReceivedGiftStorageProvider,
    private val giftClaimOperationLock: GiftClaimOperationLock,
) {
    /**
     * Parses and validates [uri]. Touches neither the network nor the chain, so it returns as fast
     * as the link can be decoded and the card can go on screen straight away.
     *
     * @throws GiftLinkException with the specific check that failed.
     */
    suspend fun preview(uri: String): GiftClaimPreview {
        // Not getSynchronizer(): that one waits for a wallet that a recipient arriving on their
        // first-ever launch does not have, and waits for it forever.
        val synchronizer = synchronizerProvider.getSynchronizerOrNull()
        val network = synchronizer?.network ?: zcashNetworkProvider()

        // Wrong network, bad version, unparseable amount, over-long message — all offline.
        val payload = GiftLinkCodec.decode(uri, network)

        // The card's address, which identifies it everywhere below: the isolated wallet's alias
        // and the receipt. Derived rather than carried, so there is nothing to disagree with.
        val cardAddress = giftKeyProvider.deriveAddress(payload.mnemonic, network)

        // Answered from the receipt, before a block is fetched. The same question asked after a
        // scan gets the same answer thirty seconds later, having searched a card it already knew
        // was empty — and a card is emptied exactly once, so the record is authoritative.
        return GiftClaimPreview(
            payload = payload,
            cardAddress = cardAddress,
            hasWallet = synchronizer != null,
            collected = collectedEarlier(payload, cardAddress, settledOnly = true),
        )
    }

    /**
     * What the recipient still has to agree to — a long foreground scan is their decision, not
     * something to spring on them (§3.6).
     *
     * @throws GiftClaimNotReadyException while the chain tip is still unknown. The card is already
     * on screen and correct by then, so this is a wait to be retried, never a verdict on the gift.
     */
    suspend fun birthdayVerdict(payload: GiftLinkPayload): GiftBirthdayVerdict {
        val synchronizer = synchronizerProvider.getSynchronizer()

        // Wait rather than fail. On the cold start an App Link produces, networkHeight is null for
        // a second or two, and reading `.value` once would reject every link opened from a chat.
        val tip =
            withTimeoutOrNull(TIP_TIMEOUT) {
                synchronizer.networkHeight
                    .filterNotNull()
                    .first()
                    .value
            } ?: throw GiftClaimNotReadyException()

        return GiftLinkCodec.evaluateBirthday(payload.birthdayHeight, tip)
    }

    /** Suspends until this device has a wallet of its own. */
    suspend fun awaitWallet() {
        synchronizerProvider.getSynchronizer()
    }

    /**
     * Syncs the card's own wallet and moves its funds here.
     *
     * Claims at least the advertised amount and sweeps spendable top-ups to the same destination.
     * Fee-reserve dust is the only value that may be intentionally abandoned at final cleanup.
     */
    suspend operator fun invoke(
        payload: GiftLinkPayload,
        cardAddress: String,
        onProgress: (GiftClaimProgress) -> Unit,
    ): GiftClaimOutcome =
        giftClaimOperationLock.withLock(cardAddress) {
            claimLocked(payload, cardAddress, onProgress)
        }

    private suspend fun claimLocked(
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

        val prepared =
            ReceivedGift(
                address = cardAddress,
                network = payload.network,
                amountZatoshi = payload.amountZatoshi.toLong(),
                claimedAt = Clock.System.now().toString(),
                destinationAddress = recipient,
                message = payload.message,
                claimLink = payload,
            )
        receivedGiftStorageProvider.record(prepared)

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

        // The prepared record already holds recovery; attach txids even if the caller was cancelled.
        if (outcome is GiftClaimOutcome.Claimed) {
            withContext(NonCancellable) {
                receivedGiftStorageProvider.record(prepared.copy(claimTxids = outcome.txIds))
            }
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
     *
     * [settledOnly] is for answering *before* a scan. An unsettled receipt means the broadcast
     * reached the mempool and nothing has confirmed it — and such a claim can still expire unmined,
     * leaving the card funded and its retained link the only way back to the money. Reporting that
     * as collected would retire a retryable claim over funds still sitting on the card. Once a scan
     * has reported the card empty the money has demonstrably moved, so any receipt answers.
     */
    private suspend fun collectedEarlier(
        payload: GiftLinkPayload,
        cardAddress: String,
        settledOnly: Boolean = false,
    ): GiftClaimOutcome.Claimed? =
        runCatching {
            val receipt =
                receivedGiftStorageProvider
                    .getAll()
                    .firstOrNull {
                        it.address == cardAddress &&
                            it.network == payload.network &&
                            it.claimTxids.isNotEmpty() &&
                            (!settledOnly || it.isSettled)
                    }
            receipt?.let { GiftClaimOutcome.Claimed(amount = Zatoshi(it.amountZatoshi), txIds = it.claimTxids) }
        }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            // Falls through to Empty, which is what this path already said before the receipt
            // existed. A store that will not read must not turn a claim into a crash.
            Twig.warn { "Received gift receipts could not be read" }
            null
        }

    private companion object {
        /** Long enough for a cold-start connection, short enough not to look frozen. */
        val TIP_TIMEOUT = 30.seconds
    }
}
