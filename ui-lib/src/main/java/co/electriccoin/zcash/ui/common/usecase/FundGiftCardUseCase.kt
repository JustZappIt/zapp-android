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
import co.electriccoin.zcash.ui.screen.gift.model.StoredGiftCard
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What funding a card costs, priced against a real proposal.
 *
 * [card] is already persisted when this exists — see [FundGiftCardUseCase.prepare]. The sender pays
 * [networkFee] *and* [claimFeeReserve] on top of [StoredGiftCard.amountZatoshi], so the recipient
 * nets exactly the card amount.
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

    /** The transaction reached lightwalletd and was rejected. Nothing was spent. */
    SUBMIT_REJECTED,

    /**
     * The broadcast neither clearly succeeded nor clearly failed — a partial submit, a gRPC failure
     * that may still have reached the network, or a throw mid-submit. Never invite a blind retry
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
 * Split in two on purpose. [prepare] mints and persists the card and prices the send without
 * spending anything, so the review screen can show real numbers; [submit] is the only call that
 * moves money. The order between them is load-bearing and must not be collapsed: the encrypted
 * record carries the only copy of the ephemeral seed and there is no reclaim, so funding an address
 * whose record had not been written yet would burn the funds permanently.
 */
class FundGiftCardUseCase(
    private val createGiftCard: CreateGiftCardUseCase,
    private val accountDataSource: AccountDataSource,
    private val proposalDataSource: ProposalDataSource,
    private val zashiSpendingKeyDataSource: ZashiSpendingKeyDataSource,
    private val giftCardStorageProvider: GiftCardStorageProvider,
) {
    /**
     * Mints a card — or re-prices [existing] — and builds its funding proposal.
     *
     * Pass [existing] to re-price a card the sender already minted but backed out of reviewing:
     * without it, every trip through the review screen would strand another unfunded draft.
     */
    suspend fun prepare(
        amount: Zatoshi,
        message: String? = null,
        expiresAt: Instant? = null,
        existing: StoredGiftCard? = null,
    ): GiftFundingQuote {
        val account = accountDataSource.getSelectedAccount()
        val fundingAmount = amount + CLAIM_FEE_RESERVE

        // Cheap refusal before minting, so an obviously unaffordable card leaves no draft behind.
        // The authoritative check is still the InsufficientFundsException below, which knows about
        // note selection and the fee this particular send needs.
        if (!account.canSpend(fundingAmount)) fail(GiftFundingError.INSUFFICIENT_FUNDS)

        val card = existing ?: createGiftCard(amount = amount, message = message, expiresAt = expiresAt)

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
     * @return the funding txid.
     */
    suspend fun submit(quote: GiftFundingQuote): String {
        val result =
            runCatching {
                proposalDataSource.submitTransaction(
                    proposal = quote.proposal.proposal,
                    usk = zashiSpendingKeyDataSource.getZashiSpendingKey(),
                )
            }.getOrElse { throwable -> throw submitFailure(throwable) }

        val txid =
            when (result) {
                is SubmitResult.Success -> result.txIds.firstOrNull()

                is SubmitResult.Failure -> fail(GiftFundingError.SUBMIT_REJECTED)

                is SubmitResult.Partial,
                is SubmitResult.GrpcFailure,
                is SubmitResult.Error,
                -> fail(GiftFundingError.SUBMIT_UNCERTAIN)
            } ?: fail(GiftFundingError.SUBMIT_UNCERTAIN)

        giftCardStorageProvider.recordFundingSubmitted(
            id = quote.card.id,
            fundingTxid = txid,
            at = Clock.System.now().toString(),
        )
        return txid
    }

    private fun proposalFailure(throwable: Throwable): Throwable =
        when (throwable) {
            is CancellationException -> {
                throwable
            }

            is InsufficientFundsException -> {
                GiftFundingException(GiftFundingError.INSUFFICIENT_FUNDS)
            }

            else -> {
                Twig.error(throwable) { "Gift card funding proposal failed" }
                GiftFundingException(GiftFundingError.PROPOSAL_FAILED)
            }
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

    private fun fail(error: GiftFundingError): Nothing = throw GiftFundingException(error)

    private companion object {
        /**
         * What the sender prepays so the recipient's claim costs them nothing.
         *
         * ZIP 317 Rev 0 is active: `fee = 5_000 x max(2, logical_actions)`. A claim spends the one
         * funding note into one output with no change, so `logical_actions = 1` and the fee floors
         * at 10,000 zatoshi — the same value as the SDK's (deprecated) `ZcashSdk.MINERS_FEE`, which
         * is not referenced directly because this module treats warnings as errors. It is a floor
         * rather than a constant; Rev 1 is drafted against NU6.3 and would raise it.
         *
         * Unlike [GiftFundingQuote.networkFee] this cannot be derived from a real proposal: at
         * funding time the card holds no notes, so there is nothing to propose a claim over.
         */
        const val CLAIM_FEE_RESERVE_ZATOSHI = 10_000L

        val CLAIM_FEE_RESERVE = Zatoshi(CLAIM_FEE_RESERVE_ZATOSHI)
    }
}
