// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.GiftClaimDataSource
import co.electriccoin.zcash.ui.common.datasource.GiftClaimOutcome
import co.electriccoin.zcash.ui.common.datasource.GiftClaimProgress
import co.electriccoin.zcash.ui.common.provider.GiftKeyProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.screen.gift.model.GiftBirthdayVerdict
import co.electriccoin.zcash.ui.screen.gift.model.GiftLinkCodec
import co.electriccoin.zcash.ui.screen.gift.model.GiftLinkError
import co.electriccoin.zcash.ui.screen.gift.model.GiftLinkException
import co.electriccoin.zcash.ui.screen.gift.model.GiftLinkPayload
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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

        return giftClaimDataSource.claim(
            payload = payload,
            network = synchronizer.network,
            endpoint = endpoint,
            recipientAddress = recipient,
            onProgress = onProgress,
        )
    }

    private companion object {
        /** Long enough for a cold-start connection, short enough not to look frozen. */
        val TIP_TIMEOUT = 30.seconds
    }
}
