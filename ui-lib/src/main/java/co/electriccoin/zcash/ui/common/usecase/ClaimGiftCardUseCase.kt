// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.GiftClaimDataSource
import co.electriccoin.zcash.ui.common.datasource.GiftClaimOutcome
import co.electriccoin.zcash.ui.common.datasource.GiftClaimProgress
import co.electriccoin.zcash.ui.common.datasource.GiftClaimResumeEvidence
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
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

/** Receipt state could not be read, so absence cannot be asserted safely. */
class GiftReceiptStoreUnreadableException : RuntimeException("Received gift receipts could not be read")

private sealed interface ReceivedGiftLookup {
    data class Found(
        val outcome: GiftClaimOutcome.Claimed,
    ) : ReceivedGiftLookup

    data object Absent : ReceivedGiftLookup

    data object Unreadable : ReceivedGiftLookup
}

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
        val collected =
            when (val lookup = collectedEarlier(payload, cardAddress, settledOnly = true)) {
                is ReceivedGiftLookup.Found -> lookup.outcome
                ReceivedGiftLookup.Absent -> null
                ReceivedGiftLookup.Unreadable -> throw GiftReceiptStoreUnreadableException()
            }
        return GiftClaimPreview(
            payload = payload,
            cardAddress = cardAddress,
            hasWallet = synchronizer != null,
            collected = collected,
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
        // Read before writing. The distinction between our interrupted submission and a second
        // holder's spend exists only in this preexisting record; manufacturing a prepared receipt
        // first would make every outgoing transaction look local.
        val existing = existingReceipt(payload, cardAddress)
        if (existing?.isSettled == true && existing.claimTxids.isNotEmpty()) {
            return GiftClaimOutcome.Claimed(Zatoshi(existing.amountZatoshi), existing.claimTxids)
        }

        val selectedAccount =
            if (existing?.destinationAddress == null) {
                accountDataSource.getSelectedAccount()
            } else {
                null
            }
        // Once an unsettled receipt names its recipient, retries stay there. Following account
        // selection would make confirmation search the wrong account or split txids across two.
        val recipient = existing?.destinationAddress ?: selectedAccount!!.unified.address.address
        val destinationAccountUuid =
            if (existing?.destinationAddress != null) {
                existing.destinationAccountUuid
            } else {
                selectedAccount!!.sdkAccount.accountUuid.toStorageKeyId()
            }
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
                destinationAccountUuid = destinationAccountUuid,
                message = payload.message,
                claimLink = payload,
            )
        receivedGiftStorageProvider.record(prepared)

        val resumeEvidence =
            GiftClaimResumeEvidence(
                claimTxIds = existing?.claimTxids.orEmpty().toSet(),
                // Old receipts predate the marker, but a recorded txid itself proves submission.
                submissionWasAttempted =
                    existing?.claimSubmissionAttemptedAt != null || existing?.claimTxids?.isNotEmpty() == true,
            )

        val outcome =
            giftClaimDataSource.claim(
                payload = payload,
                cardAddress = cardAddress,
                network = synchronizer.network,
                endpoint = endpoint,
                recipientAddress = recipient,
                resumeEvidence = resumeEvidence,
                onBeforeSubmit = {
                    // This is the irreversible boundary: if this write fails the data source must
                    // never enter its NonCancellable create-and-submit section.
                    receivedGiftStorageProvider.record(
                        prepared.copy(
                            claimTxids = emptyList(),
                            claimSubmissionAttemptedAt = Clock.System.now().toString(),
                        )
                    )
                },
                onProgress = onProgress,
            )

        if (outcome is GiftClaimOutcome.AlreadyClaimed) {
            // The final foreign spend proves this link has no recovery work left. Keeping the
            // freshly prepared scan receipt would reopen it on every foreground forever.
            withContext(NonCancellable) {
                runCatching {
                    giftClaimDataSource.cleanupFinalizedClaim(payload, cardAddress, synchronizer.network)
                }.onFailure { Twig.warn { "Gift claim: spent card wallet cleanup failed" } }
                receivedGiftStorageProvider.settle(cardAddress)
            }
            return outcome
        }

        // The prepared record already holds recovery; attach txids even if the caller was cancelled.
        val submittedTxIds =
            when (outcome) {
                is GiftClaimOutcome.Claimed -> outcome.txIds
                is GiftClaimOutcome.NotBroadcast -> outcome.result.txIds
                else -> emptyList()
            }
        if (submittedTxIds.isNotEmpty()) {
            withContext(NonCancellable) {
                val currentAttempt =
                    receivedGiftStorageProvider
                        .getAll()
                        .firstOrNull { it.address == cardAddress && it.network == payload.network }
                receivedGiftStorageProvider.record(
                    prepared.copy(
                        claimTxids = submittedTxIds,
                        claimSubmissionAttemptedAt =
                            currentAttempt?.claimSubmissionAttemptedAt ?: Clock.System.now().toString(),
                    )
                )
            }
        }
        return outcome
    }

    private suspend fun existingReceipt(
        payload: GiftLinkPayload,
        cardAddress: String,
    ): ReceivedGift? =
        try {
            receivedGiftStorageProvider
                .getAll()
                .firstOrNull { it.address == cardAddress && it.network == payload.network }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Twig.warn { "Gift claim: receipt store could not be read" }
            throw GiftReceiptStoreUnreadableException()
        }

    /**
     * The receipt for this card, absence, or an unreadable-store result. Keyed on the address,
     * which is the card's identity.
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
    ): ReceivedGiftLookup =
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
            receipt
                ?.let {
                    ReceivedGiftLookup.Found(
                        GiftClaimOutcome.Claimed(amount = Zatoshi(it.amountZatoshi), txIds = it.claimTxids)
                    )
                } ?: ReceivedGiftLookup.Absent
        }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            Twig.warn { "Received gift receipts could not be read" }
            ReceivedGiftLookup.Unreadable
        }

    private companion object {
        /** Long enough for a cold-start connection, short enough not to look frozen. */
        val TIP_TIMEOUT = 30.seconds
    }
}
