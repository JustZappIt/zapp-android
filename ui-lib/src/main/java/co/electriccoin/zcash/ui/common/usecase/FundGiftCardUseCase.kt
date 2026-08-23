// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.RegularTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.provider.GiftCardStorageProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.screen.gift.model.StoredGiftCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What funding a card costs, priced against a real proposal. [card] is already persisted when this
 * exists. The sender pays [networkFee] *and* [claimFeeReserve] on top of the card amount, so the
 * recipient nets exactly what the card says.
 */
data class GiftFundingQuote(
    val card: StoredGiftCard,
    val proposal: RegularTransactionProposal,
    val claimFeeReserve: Zatoshi,
    val networkFee: Zatoshi,
) {
    val cardAmount: Zatoshi get() = Zatoshi(card.amountZatoshi)

    /** What leaves the sender's balance: the card, the recipient's future claim fee, and this fee. */
    val total: Zatoshi get() = cardAmount + claimFeeReserve + networkFee
}

/** Why funding could not start or did not finish. Each case is a distinct thing to tell the sender. */
enum class GiftFundingError {
    /** Refused before any money moved. */
    INSUFFICIENT_FUNDS,

    /** The proposal could not be built. Nothing was sent. */
    PROPOSAL_FAILED,

    /**
     * The broadcast neither clearly succeeded nor finally failed — a partial submit, a gRPC failure,
     * a server rejection retained for SDK retry, or a throw mid-submit. Never invite a blind retry
     * from here: the first attempt may yet mine, and a card funded twice is money gone twice.
     */
    SUBMIT_UNCERTAIN,
}

class GiftFundingException(
    val error: GiftFundingError,
) : RuntimeException(error.name)

/**
 * Moves money onto a minted gift card.
 *
 * Split in two on purpose: [prepare] mints, persists and prices without spending, so the review
 * screen can show real numbers, and [submit] is the only call that moves money. The order is
 * load-bearing and must not be collapsed — the encrypted record holds the only copy of the
 * ephemeral seed, so funding an address whose record was not yet written burns the funds.
 */
@Suppress("TooManyFunctions")
class FundGiftCardUseCase(
    private val createGiftCard: CreateGiftCardUseCase,
    private val accountDataSource: AccountDataSource,
    private val proposalDataSource: ProposalDataSource,
    private val zashiSpendingKeyDataSource: ZashiSpendingKeyDataSource,
    private val giftCardStorageProvider: GiftCardStorageProvider,
    private val persistableWalletProvider: PersistableWalletProvider,
) {
    /**
     * Mints a card — or re-prices [existing] — and builds its funding proposal.
     *
     * Pass [existing] for a card the sender minted but backed out of reviewing; without it, every
     * trip through the review screen strands another unfunded draft. One already carrying a funding
     * attempt is refused outright.
     */
    suspend fun prepare(
        amount: Zatoshi,
        message: String? = null,
        expiresAt: Instant? = null,
        existing: StoredGiftCard? = null,
    ): GiftFundingQuote {
        // Re-read rather than trust the copy handed in. The caller's is a snapshot held across a
        // screen the sender can leave and come back to, so it can be stale in both directions: the
        // record may have been superseded by a later mint and no longer exist, and — the half that
        // costs money — it may have picked up a funding attempt that this copy does not show.
        val current = existing?.let { giftCardStorageProvider.get(it.id) }

        // The durable gate on double funding. The screen's error state cannot be it: stepping back
        // to the details and continuing again clears that error and lands here with the same card.
        if (current?.hasFundingAttempt == true) fail(GiftFundingError.SUBMIT_UNCERTAIN)

        val account = accountDataSource.getSelectedAccount()
        val fundingAmount = amount + CLAIM_FEE_RESERVE

        // Cheap refusal before minting, so an obviously unaffordable card leaves no draft behind.
        // The authoritative check is still the InsufficientFundsException below, which knows about
        // note selection and the fee this particular send needs.
        if (!account.canSpend(fundingAmount)) fail(GiftFundingError.INSUFFICIENT_FUNDS)

        // A draft that is gone was superseded by a later mint, so this mints again rather than
        // pricing a record nothing can fund.
        val card = current ?: createGiftCard(amount = amount, message = message, expiresAt = expiresAt)

        val proposal =
            runCatching {
                proposalDataSource.createProposal(
                    account = account,
                    send =
                        ZecSend(
                            destination = WalletAddress.Unified.new(card.address),
                            amount = fundingAmount,
                            // No memo. A memo would be readable by whoever claims the card and by
                            // nobody else, and the sender's message already rides in the link,
                            // where it costs no chain space and leaks nothing on-chain.
                            memo = Memo(""),
                            proposal = null,
                        )
                )
            }.getOrElse { throwable -> throw proposalFailure(throwable) }

        return GiftFundingQuote(
            card = card,
            proposal = proposal,
            claimFeeReserve = CLAIM_FEE_RESERVE,
            networkFee = proposal.proposal.totalFeeRequired(),
        )
    }

    /**
     * Broadcasts [quote]'s funding transaction and records the txid against the card.
     *
     * The card stays a draft afterwards: `recordFundingSubmitted` claims only that a transaction
     * exists, not that it mined. Advancing it to funded is [ConfirmGiftCardFundingUseCase]'s job,
     * once there is a block behind it.
     *
     * The durable start marker divides this method: before it, failures are
     * [GiftFundingError.PROPOSAL_FAILED]; from it onwards — creation and storage writes included —
     * [GiftFundingError.SUBMIT_UNCERTAIN]. Slipstream can resubmit a created transaction before the
     * app explicitly submits it, and can retry one after a server rejection.
     *
     * @return the funding txid.
     */
    suspend fun submit(quote: GiftFundingQuote): String {
        // Key and endpoint lookup happen before the durable boundary and cannot create a
        // transaction, so failures here remain safe to retry.
        val usk =
            runCatching { zashiSpendingKeyDataSource.getZashiSpendingKey() }
                .getOrElse(::failProposal)
        val endpoint =
            runCatching { persistableWalletProvider.requirePersistableWallet().endpoint }
                .getOrElse(::failProposal)

        // Slipstream may resubmit a transaction merely because it exists in the wallet database,
        // even before Broadcaster.submit is called. Persist the unresolved gate before creation so
        // no crash or storage failure can leave an auto-broadcast transaction behind an
        // "unfunded" card that is later discarded or funded again.
        runCatching { markAttempted(quote.card.id) }.getOrElse(::failProposal)

        return withContext(NonCancellable) {
            val transaction =
                runCatching {
                    proposalDataSource
                        .createTransactions(quote.proposal.proposal, usk)
                        .single()
                }.getOrElse(::failStarted)

            runCatching { recordCreated(quote.card.id, transaction.txIdString()) }
                .getOrElse(::failRecord)

            val result =
                runCatching {
                    proposalDataSource.submitTransaction(
                        transaction = transaction,
                        endpoint = endpoint,
                    )
                }.getOrElse(::failSubmit)

            val txid =
                when (result) {
                    is SubmitResult.Success -> {
                        result.txIds.firstOrNull()
                    }

                    is SubmitResult.Failure,
                    is SubmitResult.Partial,
                    is SubmitResult.GrpcFailure,
                    is SubmitResult.Error,
                    -> {
                        // None of these results makes the locally-created transaction ineligible for
                        // automatic SDK resubmission, including an RPC rejection. Clearing its txid
                        // here could make a later retry fund a card the app now considers abandoned.
                        fail(GiftFundingError.SUBMIT_UNCERTAIN)
                    }
                } ?: fail(GiftFundingError.SUBMIT_UNCERTAIN)

            recordSubmitted(cardId = quote.card.id, txid = txid)
            txid
        }
    }

    private suspend fun recordCreated(cardId: String, txid: String) {
        giftCardStorageProvider.recordFundingCreated(
            id = cardId,
            fundingTxid = txid,
            at = Clock.System.now().toString(),
        )
    }

    /** Flags funding as unresolved before the SDK is allowed to create an outgoing transaction. */
    private suspend fun markAttempted(cardId: String) {
        giftCardStorageProvider.setFundingAttemptedAt(id = cardId, at = Clock.System.now().toString())
    }

    /**
     * Records the txid, clearing the attempt flag as a side effect: a txid is a stronger record of
     * the same fact. Past the broadcast, so a refusal here loses the record of where the money went,
     * not the money — and cannot be reported as a funding that never happened.
     */
    private suspend fun recordSubmitted(cardId: String, txid: String) {
        runCatching {
            giftCardStorageProvider.recordFundingSubmitted(
                id = cardId,
                fundingTxid = txid,
                at = Clock.System.now().toString(),
            )
        }.getOrElse(::failRecord)
    }

    private fun failProposal(throwable: Throwable): Nothing = throw proposalFailure(throwable)

    private fun failStarted(throwable: Throwable): Nothing = throw startedFailure(throwable)

    private fun failSubmit(throwable: Throwable): Nothing = throw submitFailure(throwable)

    private fun failRecord(throwable: Throwable): Nothing = throw recordFailure(throwable)

    private fun proposalFailure(throwable: Throwable): Throwable =
        when (throwable) {
            is CancellationException -> {
                throwable
            }

            is InsufficientFundsException -> {
                GiftFundingException(GiftFundingError.INSUFFICIENT_FUNDS)
            }

            else -> {
                Twig.error(throwable) { "Gift card funding could not start" }
                GiftFundingException(GiftFundingError.PROPOSAL_FAILED)
            }
        }

    private fun startedFailure(throwable: Throwable): Throwable =
        if (throwable is CancellationException) {
            throwable
        } else {
            Twig.error(throwable) { "Gift card funding transaction creation became uncertain" }
            GiftFundingException(GiftFundingError.SUBMIT_UNCERTAIN)
        }

    // A throw out of submit says nothing about whether the transaction reached the network, so it
    // is reported as uncertain rather than as a clean failure.
    private fun submitFailure(throwable: Throwable): Throwable =
        if (throwable is CancellationException) {
            throwable
        } else {
            Twig.error(throwable) { "Gift card funding submit threw" }
            GiftFundingException(GiftFundingError.SUBMIT_UNCERTAIN)
        }

    private fun recordFailure(throwable: Throwable): Throwable =
        if (throwable is CancellationException) {
            throwable
        } else {
            Twig.error(throwable) { "Gift card funding txid could not be recorded" }
            GiftFundingException(GiftFundingError.SUBMIT_UNCERTAIN)
        }

    private fun fail(error: GiftFundingError): Nothing = throw GiftFundingException(error)

    private companion object {
        /**
         * What the sender prepays so the recipient's claim costs them nothing.
         *
         * ZIP 317 Rev 0: `fee = 5_000 x max(2, logical_actions)`. A claim spends one funding note
         * into one output with no change, so the fee floors at 10,000 zatoshi — the same value as
         * the SDK's deprecated `ZcashSdk.MINERS_FEE`, not referenced directly because this module
         * treats warnings as errors. A floor, not a constant: Rev 1 against NU6.3 would raise it.
         *
         * Unlike [GiftFundingQuote.networkFee] this cannot come from a real proposal — at funding
         * time the card holds no notes, so there is nothing to propose a claim over.
         */
        const val CLAIM_FEE_RESERVE_ZATOSHI = 10_000L

        val CLAIM_FEE_RESERVE = Zatoshi(CLAIM_FEE_RESERVE_ZATOSHI)
    }
}
