// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.ext.convertZecToZatoshi
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.provider.GiftCardStorageProvider
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.security.PinVerifyState
import co.electriccoin.zcash.ui.common.security.SecretAuthGate
import co.electriccoin.zcash.ui.common.security.SecretAuthPolicy
import co.electriccoin.zcash.ui.common.usecase.ConfirmGiftCardFundingUseCase
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.FundGiftCardUseCase
import co.electriccoin.zcash.ui.common.usecase.GiftCardCreationError
import co.electriccoin.zcash.ui.common.usecase.GiftCardCreationException
import co.electriccoin.zcash.ui.common.usecase.GiftFundingError
import co.electriccoin.zcash.ui.common.usecase.GiftFundingException
import co.electriccoin.zcash.ui.common.usecase.GiftFundingQuote
import co.electriccoin.zcash.ui.common.usecase.ShareGiftLinkUseCase
import co.electriccoin.zcash.ui.common.wallet.ZecFiatRate
import co.electriccoin.zcash.ui.common.wallet.toFiatString
import co.electriccoin.zcash.ui.common.wallet.zecFiatRate
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.gift.model.GiftLinkCodec
import co.electriccoin.zcash.ui.screen.gift.model.GiftMessage
import co.electriccoin.zcash.ui.screen.gift.model.toLinkPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * Drives the create-a-gift-card flow: enter an amount, review what it costs, fund it, share it.
 *
 * One ordering carries the whole feature. Minting, persisting and encoding the link all happen in
 * [prepare], entirely before [fund] moves any money — a record that will not encode is a card whose
 * funds nobody could ever reach, and there is no reclaim, so it has to be caught while the money is
 * still in the sender's wallet.
 */
@Suppress("TooManyFunctions")
class GiftCardVM(
    private val fundGiftCard: FundGiftCardUseCase,
    private val confirmGiftCardFunding: ConfirmGiftCardFundingUseCase,
    private val shareGiftLink: ShareGiftLinkUseCase,
    private val copyToClipboard: CopyToClipboardUseCase,
    private val secretAuthGate: SecretAuthGate,
    accountDataSource: AccountDataSource,
    exchangeRateRepository: ExchangeRateRepository,
    swapRepository: SwapRepository,
    giftCardStorageProvider: GiftCardStorageProvider,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val snapshot = MutableStateFlow(GiftCardSnapshot())

    private var prepareJob: Job? = null
    private var fundJob: Job? = null
    private var copyFeedbackJob: Job? = null

    /** What the current draft was minted for, so backing out to edit does not strand it. */
    private var preparedFor: GiftCardInputs? = null

    /**
     * Resolved the way the balance card resolves it — the opt-in rate first, then the swap
     * catalog's ZEC price — so a card never shows a figure the home screen disagrees with.
     * Null means show no fiat at all.
     */
    private val fiatRate =
        combine(exchangeRateRepository.state, swapRepository.assets) { rate, assets ->
            zecFiatRate(rate, assets.zecAsset?.usdPrice)
        }

    internal val state: StateFlow<GiftCardState> =
        combine(
            snapshot,
            secretAuthGate.pinPrompt,
            accountDataSource.selectedAccount,
            giftCardStorageProvider.observe().catch { emit(emptyList()) },
            fiatRate,
        ) { current, pin, account, storedCards, rate ->
            current.toState(
                pinVerify = pin,
                spendableBalance = account?.spendableShieldedBalance?.let { stringRes(it) },
                hasStoredCards = storedCards.isNotEmpty(),
                rate = rate,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            // The same mapper, so a field added to the state cannot reach the screen wired here
            // and unwired before the first emission.
            initialValue =
                GiftCardSnapshot().toState(
                    pinVerify = null,
                    spendableBalance = null,
                    hasStoredCards = false,
                    rate = null,
                ),
        )

    private fun GiftCardSnapshot.toState(
        pinVerify: PinVerifyState?,
        spendableBalance: StringResource?,
        hasStoredCards: Boolean,
        rate: ZecFiatRate?,
    ): GiftCardState {
        // From DETAILS, and from the one REVIEW the sender cannot fund their way out of.
        val canOpenSavedCards =
            hasStoredCards &&
                when (stage) {
                    GiftCardStage.DETAILS -> true
                    GiftCardStage.REVIEW -> error == GiftCardError.SUBMIT_UNCERTAIN
                    else -> false
                }
        // What is typed wins on DETAILS, even when a quote is already in hand: backing out of
        // REVIEW keeps the quote, and preferring it there froze the preview on the old figure
        // while the sender typed a new one.
        val typed = runCatching { amount.amount?.convertZecToZatoshi() }.getOrNull()
        val shown = if (stage == GiftCardStage.DETAILS) typed else quote?.cardAmount ?: typed
        return GiftCardState(
            stage = stage,
            previewAmount = shown,
            // Null wherever the wallet has no rate — opted out, or not loaded yet. Then no fiat.
            fiat = shown?.let { zec -> rate?.toFiatString(zec) },
            amount =
                NumberTextFieldState(
                    innerState = amount,
                    isEnabled = stage == GiftCardStage.DETAILS,
                    onValueChange = ::onAmountChange,
                ),
            spendableBalance = spendableBalance,
            message = message,
            messageGraphemes = GiftMessage.graphemeCount(message),
            expiry = expiry,
            quote = quote,
            link = link,
            isCopied = isCopied,
            isAuthenticating = isAuthenticating,
            error = error,
            pinVerify = pinVerify,
            onAmountChange = ::onAmountChange,
            onMessageChange = ::onMessageChange,
            onExpiryChange = ::onExpiryChange,
            onContinue = ::onContinue,
            onConfirm = ::onConfirm,
            onCopy = ::onCopy,
            onShare = ::onShare,
            onDone = navigationRouter::back,
            onBack = ::onBack,
            onOpenSavedCards = { navigationRouter.forward(GiftCardListArgs) }.takeIf { canOpenSavedCards },
        )
    }

    init {
        // Picks up any card whose funding mined while nothing was watching, because a previous run
        // was killed between broadcast and the next block.
        viewModelScope.launch { confirmGiftCardFunding.reconcile() }
    }

    private fun onAmountChange(amount: NumberTextFieldInnerState) =
        snapshot.update { it.copy(amount = amount, error = null) }

    private fun onMessageChange(message: String) = snapshot.update { it.copy(message = message, error = null) }

    private fun onExpiryChange(expiry: GiftExpiry) = snapshot.update { it.copy(expiry = expiry, error = null) }

    private fun onContinue() {
        val current = snapshot.value
        if (prepareJob?.isActive == true || current.stage != GiftCardStage.DETAILS) return

        val zec = current.amount.amount
        if (zec == null || zec <= BigDecimal.ZERO) {
            snapshot.update { it.copy(error = GiftCardError.AMOUNT_INVALID) }
            return
        }

        val inputs =
            GiftCardInputs(amount = zec.convertZecToZatoshi(), message = current.note(), expiry = current.expiry)
        snapshot.update { it.copy(stage = GiftCardStage.PREPARING, error = null) }
        prepareJob = viewModelScope.launch { prepare(inputs) }
    }

    private suspend fun prepare(inputs: GiftCardInputs) {
        // Reuse the draft only when nothing it was minted for has changed: the amount, the message
        // and the expiry are all baked into the persisted record and into the link.
        val reusable =
            snapshot.value.quote
                ?.card
                ?.takeIf { preparedFor == inputs }
        runCatching {
            val quote =
                fundGiftCard.prepare(
                    amount = inputs.amount,
                    message = inputs.message,
                    expiresAt =
                        inputs.expiry.days
                            ?.let { Clock.System.now() + it.days },
                    existing = reusable,
                )
            quote to GiftLinkCodec.encode(quote.card.toLinkPayload())
        }.onSuccess { (quote, link) ->
            preparedFor = inputs
            snapshot.update { it.copy(stage = GiftCardStage.REVIEW, quote = quote, link = link, error = null) }
        }.onFailure { throwable ->
            if (throwable is CancellationException) throw throwable
            snapshot.update { it.copy(stage = GiftCardStage.DETAILS, error = throwable.toGiftCardError()) }
        }
    }

    private fun onConfirm() {
        val current = snapshot.value
        if (fundJob?.isActive == true ||
            current.stage != GiftCardStage.REVIEW ||
            current.error == GiftCardError.SUBMIT_UNCERTAIN
        ) {
            return
        }
        val quote = current.quote ?: return
        fundJob = viewModelScope.launch { fund(quote) }
    }

    private suspend fun fund(quote: GiftFundingQuote) {
        snapshot.update { it.copy(isAuthenticating = true, error = null) }
        val authenticated =
            secretAuthGate.authenticate(
                promptMessage = stringRes(R.string.gift_card_auth_prompt),
                policy = SecretAuthPolicy.REQUIRE_AUTHENTICATION,
            )
        if (!authenticated) {
            snapshot.update { it.copy(isAuthenticating = false, error = GiftCardError.AUTHENTICATION_FAILED) }
            return
        }

        snapshot.update { it.copy(isAuthenticating = false, stage = GiftCardStage.FUNDING) }
        runCatching { fundGiftCard.submit(quote) }
            .onSuccess { txid ->
                snapshot.update { it.copy(stage = GiftCardStage.READY, error = null) }
                // Detached from this job: the card is already shareable, and this only advances the
                // record from submitted to funded once a block lands behind the transaction.
                viewModelScope.launch { confirmGiftCardFunding(quote.card.id, txid) }
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                snapshot.update { it.copy(stage = GiftCardStage.REVIEW, error = throwable.toGiftCardError()) }
            }
    }

    private fun onCopy() {
        val current = snapshot.value
        val link = current.link.takeIf { current.stage == GiftCardStage.READY } ?: return
        val cardId = current.quote?.card?.id ?: return
        copyToClipboard(link, isSensitive = true)
        snapshot.update { it.copy(isCopied = true, error = null) }
        // The clipboard is a hand-off too. Recording it is what eventually unblocks deleting the
        // source account for a card that has in fact already been given away — and if that record
        // does not save, the sender is the only one who can do anything about it. Launched after
        // the copy feedback is set, so a failure lands on top of it rather than under it.
        viewModelScope.launch {
            if (!shareGiftLink.markHandedOut(cardId)) {
                snapshot.update { it.copy(error = GiftCardError.HANDOFF_FAILED) }
            }
        }
        copyFeedbackJob?.cancel()
        copyFeedbackJob =
            viewModelScope.launch {
                delay(COPY_FEEDBACK_DURATION_MS)
                snapshot.update { it.copy(isCopied = false) }
            }
    }

    private fun onShare(sharePickerText: String) {
        val current = snapshot.value
        val link = current.link.takeIf { current.stage == GiftCardStage.READY } ?: return
        val cardId = current.quote?.card?.id ?: return
        snapshot.update { it.copy(error = null) }
        viewModelScope.launch {
            if (!shareGiftLink(cardId = cardId, link = link, sharePickerText = sharePickerText)) {
                snapshot.update { it.copy(error = GiftCardError.SHARE_FAILED) }
            }
        }
    }

    private fun onBack() {
        when (snapshot.value.stage) {
            GiftCardStage.DETAILS -> navigationRouter.back()

            GiftCardStage.REVIEW -> snapshot.update { it.copy(stage = GiftCardStage.DETAILS, error = null) }

            // Funding is irreversible, and the ready screen is the only place the link is shown.
            GiftCardStage.PREPARING, GiftCardStage.FUNDING, GiftCardStage.READY -> Unit
        }
    }

    private companion object {
        const val COPY_FEEDBACK_DURATION_MS = 2_000L
    }
}

/** The inputs a draft was minted for. Re-minting on an unchanged set would strand the old draft. */
private data class GiftCardInputs(
    val amount: Zatoshi,
    val message: String?,
    val expiry: GiftExpiry,
)

private data class GiftCardSnapshot(
    val stage: GiftCardStage = GiftCardStage.DETAILS,
    val amount: NumberTextFieldInnerState = NumberTextFieldInnerState(),
    val message: String = "",
    val expiry: GiftExpiry = GiftExpiry.NEVER,
    val quote: GiftFundingQuote? = null,
    val link: String? = null,
    val isCopied: Boolean = false,
    val isAuthenticating: Boolean = false,
    val error: GiftCardError? = null,
) {
    // The link is the money: a generated toString would drop it into any log line or crash report
    // that interpolates the snapshot.
    override fun toString(): String = "GiftCardSnapshot(stage=$stage, error=$error, redacted)"

    fun note(): String? = message.trim().takeIf { it.isNotEmpty() }
}

private fun Throwable.toGiftCardError(): GiftCardError =
    when (this) {
        is GiftFundingException -> {
            when (error) {
                GiftFundingError.INSUFFICIENT_FUNDS -> GiftCardError.INSUFFICIENT_FUNDS
                GiftFundingError.PROPOSAL_FAILED -> GiftCardError.PROPOSAL_FAILED
                GiftFundingError.SUBMIT_REJECTED -> GiftCardError.SUBMIT_REJECTED
                GiftFundingError.SUBMIT_UNCERTAIN -> GiftCardError.SUBMIT_UNCERTAIN
            }
        }

        is GiftCardCreationException -> {
            when (error) {
                GiftCardCreationError.INVALID_AMOUNT -> GiftCardError.AMOUNT_INVALID
                GiftCardCreationError.MESSAGE_TOO_LONG -> GiftCardError.MESSAGE_TOO_LONG
                GiftCardCreationError.KEYSTONE_ACCOUNT_UNSUPPORTED -> GiftCardError.KEYSTONE_UNSUPPORTED
                GiftCardCreationError.UNSUPPORTED_NETWORK -> GiftCardError.UNSUPPORTED_NETWORK
                GiftCardCreationError.CHAIN_TIP_UNAVAILABLE -> GiftCardError.CHAIN_TIP_UNAVAILABLE
                GiftCardCreationError.PERSIST_FAILED -> GiftCardError.PERSIST_FAILED
            }
        }

        // Includes a GiftLinkException out of the encode. Log the throwable, never a message that
        // interpolates the payload: kotlinx embeds the input it failed on, which is the mnemonic.
        else -> {
            Twig.error(this) { "Gift card could not be prepared" }
            GiftCardError.MINT_FAILED
        }
    }
