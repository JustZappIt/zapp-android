// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.spackle.Twig
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
 * A link that has been checked as far as it can be without touching the network.
 *
 * [verdict] is what the recipient still has to agree to: a card old enough to need a long
 * foreground scan is their decision, not something to spring on them (§3.6).
 */
data class GiftClaimPreview(
    val payload: GiftLinkPayload,
    val verdict: GiftBirthdayVerdict,
)

/**
 * The wallet does not yet know the chain tip, so how far back this card sits cannot be judged.
 *
 * Deliberately distinct from [GiftLinkError.BIRTHDAY_ABOVE_TIP]: that one means the *card* is
 * wrong, this one means *we* are not ready. Telling a recipient their perfectly good card claims to
 * be from the future — and offering no way to retry — is the kind of message that makes someone
 * think a real gift is fake.
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

        // Rejects a wrong network, a bad version, an unparseable amount and an over-long message,
        // all offline.
        val payload = GiftLinkCodec.decode(uri, synchronizer.network)

        // Then the one check that needs key derivation: the address in the link must be the one
        // its own mnemonic produces. A mismatch means the link was rewritten, and scanning for a
        // note at an address we cannot spend from would be pure wasted work.
        val derived = giftKeyProvider.deriveAddress(payload.mnemonic, synchronizer.network)
        GiftLinkCodec.verifyAddressMatches(payload, derived)

        // Wait rather than fail. On a cold start — which is exactly what an App Link produces —
        // the synchronizer has not connected yet and networkHeight is still null for a second or
        // two. Reading `.value` once would reject every link opened from a chat app.
        val tip =
            withTimeoutOrNull(TIP_TIMEOUT) {
                synchronizer.networkHeight
                    .filterNotNull()
                    .first()
                    .value
            } ?: throw GiftClaimNotReadyException()

        return GiftClaimPreview(
            payload = payload,
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
        if (outcome is GiftClaimOutcome.Empty) collectedEarlier(payload)?.let { return it }

        // NonCancellable for the same reason `GiftClaimDataSource` deletes under it: the money has
        // already moved by the time this runs, and a cancelled scope would drop the only durable
        // record that it did.
        if (outcome is GiftClaimOutcome.Claimed) withContext(NonCancellable) { recordReceipt(payload, outcome) }
        return outcome
    }

    /**
     * The receipt for this card, rebuilt as the outcome it came from, or null if there is none.
     *
     * Keyed on the card's address, which is its identity: one card is one ephemeral wallet, and
     * `ReceivedGift` already keeps one receipt per address however many times a link is opened.
     *
     * The narrow case this reads wrong is a card whose address is funded a second time and emptied
     * again — it would report the first collection rather than the second. Nothing in the app can
     * re-fund a spent card, it would take a hand-built transaction to that address, and the answer
     * it gives then is still closer to true than "nothing left on this card".
     */
    private suspend fun collectedEarlier(payload: GiftLinkPayload): GiftClaimOutcome.Claimed? =
        runCatching {
            receivedGiftStorageProvider
                .getAll()
                .firstOrNull { it.address == payload.address && it.network == payload.network }
                ?.let { GiftClaimOutcome.Claimed(amount = Zatoshi(it.amountZatoshi), txIds = it.claimTxids) }
        }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            // Falls through to Empty, which is what this path already said before the receipt
            // existed. A store that will not read must not turn a claim into a crash.
            Twig.warn { "Received gift receipts could not be read" }
            null
        }

    /**
     * Best effort, and deliberately so. The money is already in the wallet by this point; a store
     * that will not take the receipt costs the recipient a row in a list, not their gift.
     */
    private suspend fun recordReceipt(payload: GiftLinkPayload, outcome: GiftClaimOutcome.Claimed) {
        runCatching {
            receivedGiftStorageProvider.record(
                ReceivedGift(
                    address = payload.address,
                    network = payload.network,
                    amountZatoshi = outcome.amount.value,
                    claimedAt = Clock.System.now().toString(),
                    claimTxids = outcome.txIds,
                    message = payload.message,
                )
            )
        }.onFailure { throwable ->
            if (throwable is CancellationException) throw throwable
            Twig.warn { "Claimed gift could not be recorded" }
        }
    }

    private companion object {
        /** Long enough for a cold-start connection, short enough not to look frozen. */
        val TIP_TIMEOUT = 30.seconds
    }
}
