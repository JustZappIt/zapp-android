package co.electriccoin.zcash.ui.screen.invest.buy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.ext.convertZatoshiToZec
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.invest.model.BuyEstimate
import co.electriccoin.zcash.ui.common.invest.model.InvestAssets
import co.electriccoin.zcash.ui.common.invest.model.PreparedBuy
import co.electriccoin.zcash.ui.common.invest.provider.InvestApiException
import co.electriccoin.zcash.ui.common.invest.repository.InvestRepository
import co.electriccoin.zcash.ui.common.provider.BridgeAuthorizationCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.common.InvestFormat
import co.electriccoin.zcash.ui.screen.invest.common.investCatching
import co.electriccoin.zcash.ui.screen.invest.common.toInvestMessage
import co.electriccoin.zcash.ui.screen.invest.progress.InvestProgressArgs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Clock
import kotlin.time.toJavaInstant

/**
 * I5 and its review sheet I6. The amount is in USD; a dry quote runs once typing has stopped for
 * [AMOUNT_SETTLE_DELAY_MS]. "Review" fetches a live quote the repository has checked against the request, and the
 * sheet holds it until [PreparedBuy.expiresAt]; after that the only way on is a fresh one. Confirming hands the
 * quote to the repository, which runs the existing authenticated send; a cancelled authentication leaves the user
 * on the sheet.
 */
@Suppress("TooManyFunctions")
internal class InvestBuyVM(
    args: InvestBuyArgs,
    private val investRepository: InvestRepository,
    accountDataSource: AccountDataSource,
    swapRepository: SwapRepository,
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val navigationRouter: NavigationRouter,
    private val clock: Clock,
) : ViewModel() {
    private val asset = checkNotNull(InvestAssets.find(args.assetId)) { "Not a curated Invest asset" }

    private val amount = MutableStateFlow(NumberTextFieldInnerState())
    private val quote = MutableStateFlow<BuyQuote>(BuyQuote.Idle)
    private val isRetrying = MutableStateFlow(false)
    private val isPreparing = MutableStateFlow(false)

    private data class Review(
        val prepared: PreparedBuy,
        val remainingSeconds: Long,
        val isBusy: Boolean = false,
        val error: StringResource? = null,
    )

    private val review = MutableStateFlow<Review?>(null)
    private var countdownJob: Job? = null

    private val spendableZec =
        accountDataSource.selectedAccount
            .map { it?.spendableShieldedBalance ?: Zatoshi(0) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, Zatoshi(0))
    private val zecUsd =
        swapRepository.assets
            .map { it.zecAsset?.usdPrice }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                swapRepository.assets.value.zecAsset
                    ?.usdPrice
            )

    init {
        // collectLatest cancels the previous block, so the leading delay is the debounce: one quote per pause.
        viewModelScope.launch {
            amount
                .map { it.amount }
                .distinctUntilChanged()
                .collectLatest { usd ->
                    if (usd == null || usd.signum() <= 0) {
                        quote.update { BuyQuote.Idle }
                        return@collectLatest
                    }
                    quote.update { if (usd >= InvestRepository.MINIMUM_USD) BuyQuote.Loading else BuyQuote.Idle }
                    delay(AMOUNT_SETTLE_DELAY_MS)
                    requestEstimate(usd)
                }
        }
    }

    val state: StateFlow<InvestBuyState> =
        combine(amount, quote, isRetrying, isPreparing, combine(spendableZec, zecUsd, ::Pair)) {
            amt,
            current,
            retrying,
            preparing,
            (spendable, price),
            ->
            buildState(amt, current, retrying, preparing, spendable, price)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue =
                buildState(amount.value, quote.value, false, false, spendableZec.value, zecUsd.value),
        )

    val reviewState: StateFlow<InvestReviewState?> =
        review
            .map { it?.let(::buildReview) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT), null)

    private suspend fun requestEstimate(usd: BigDecimal) {
        if (usd < InvestRepository.MINIMUM_USD) {
            quote.update { BuyQuote.Ready(BuyEstimate.BelowMinimum(InvestRepository.MINIMUM_USD)) }
            return
        }
        val result = investCatching { investRepository.estimateBuy(asset, usd) }
        // A figure for an amount the user has already changed is worth nothing.
        if (amount.value.amount != usd) return
        quote.update {
            result.fold(
                onSuccess = { BuyQuote.Ready(it) },
                onFailure = { e ->
                    if (e is InvestApiException.NoPrice) {
                        BuyQuote.Ready(BuyEstimate.NoPrice)
                    } else {
                        Twig.warn(e) { "InvestBuyVM: estimate failed" }
                        BuyQuote.Failed(e.toInvestMessage())
                    }
                },
            )
        }
    }

    private fun buildState(
        amt: NumberTextFieldInnerState,
        current: BuyQuote,
        retrying: Boolean,
        preparing: Boolean,
        spendable: Zatoshi,
        price: BigDecimal?,
    ): InvestBuyState {
        val estimate = (current as? BuyQuote.Ready)?.estimate
        val (notice, isDanger) = InvestBuyPresenter.notice(current)
        return InvestBuyState(
            title = stringRes(R.string.invest_buy_title, asset.name),
            amountInput = NumberTextFieldState(innerState = amt, onValueChange = ::onAmountChange),
            balanceText = balanceText(spendable, price),
            presets = presets(maxUsd(spendable, price)),
            ledger = InvestBuyPresenter.ledger(current, asset, amt.amount),
            noPrice =
                if (estimate is BuyEstimate.NoPrice) {
                    InvestNoPriceState(
                        body = InvestBuyPresenter.noPriceBody(asset),
                        reopen = InvestBuyPresenter.reopen(asset, clock.now().toJavaInstant()),
                        isRetrying = retrying,
                        onTryAgain = ::onTryAgain,
                    )
                } else {
                    null
                },
            notice = notice,
            isNoticeDanger = isDanger,
            isAmountError = estimate is BuyEstimate.InsufficientZec,
            primaryButton =
                ButtonState(
                    text = stringRes(R.string.invest_buy_review),
                    isEnabled = estimate is BuyEstimate.Priced && !preparing,
                    onClick = ::onReview,
                ),
            isPreparing = preparing,
            onBack = navigationRouter::back,
        )
    }

    private fun balanceText(
        spendable: Zatoshi,
        price: BigDecimal?,
    ): StringResource {
        val zec = spendable.convertZatoshiToZec()
        val zecText = InvestFormat.zec(zec)
        return if (price == null) {
            stringRes(zecText)
        } else {
            stringRes(R.string.invest_buy_balance_value, zecText, InvestFormat.usd(zec.multiply(price)))
        }
    }

    private fun presets(maxUsd: BigDecimal?): List<InvestPresetState> =
        PRESETS_USD.map { usd ->
            InvestPresetState(stringRes(InvestFormat.usd(usd).removeSuffix(".00")), isEnabled = true) {
                setAmount(usd)
            }
        } +
            InvestPresetState(stringRes(R.string.invest_buy_preset_max), isEnabled = maxUsd != null) {
                maxUsd?.let(::setAmount)
            }

    // Max leaves room for the network fee and the quote's own slippage, so it doesn't land on "not enough ZEC".
    private fun maxUsd(
        spendable: Zatoshi,
        price: BigDecimal?,
    ): BigDecimal? {
        val zec = spendable.convertZatoshiToZec().subtract(MAX_FEE_RESERVE_ZEC)
        if (price == null || price.signum() <= 0 || zec.signum() <= 0) return null
        return zec
            .multiply(price)
            .multiply(MAX_HEADROOM)
            .setScale(2, RoundingMode.DOWN)
            .takeIf { it.signum() > 0 }
    }

    private fun setAmount(usd: BigDecimal) = amount.update { NumberTextFieldInnerState.fromAmount(usd) }

    private fun onAmountChange(next: NumberTextFieldInnerState) = amount.update { next }

    private fun onTryAgain() {
        val usd = amount.value.amount ?: return
        if (isRetrying.value) return
        isRetrying.update { true }
        viewModelScope.launch {
            requestEstimate(usd)
            isRetrying.update { false }
        }
    }

    private fun onReview() {
        val usd = amount.value.amount ?: return
        if (isPreparing.value) return
        isPreparing.update { true }
        viewModelScope.launch {
            investCatching { investRepository.prepareBuy(asset, usd) }
                .onSuccess(::openReview)
                .onFailure { e ->
                    Twig.warn(e) { "InvestBuyVM: prepareBuy failed" }
                    quote.update {
                        if (e is InvestApiException.NoPrice) {
                            BuyQuote.Ready(BuyEstimate.NoPrice)
                        } else {
                            BuyQuote.Failed(e.toInvestMessage())
                        }
                    }
                }
            isPreparing.update { false }
        }
    }

    private fun openReview(prepared: PreparedBuy) {
        review.update { Review(prepared, remainingSeconds(prepared)) }
        countdownJob?.cancel()
        countdownJob =
            viewModelScope.launch {
                var remaining = review.value?.let { remainingSeconds(it.prepared) } ?: 0
                while (remaining > 0) {
                    delay(COUNTDOWN_TICK_MS)
                    remaining = review.value?.let { remainingSeconds(it.prepared) } ?: 0
                    review.update { it?.copy(remainingSeconds = remaining) }
                }
            }
    }

    private fun remainingSeconds(prepared: PreparedBuy): Long =
        (prepared.expiresAt - clock.now()).inWholeSeconds.coerceAtLeast(0)

    private fun buildReview(current: Review): InvestReviewState {
        val figures = InvestBuyPresenter.review(current.prepared, current.remainingSeconds)
        val isExpired = current.remainingSeconds <= 0
        return InvestReviewState(
            youSend = figures.youSend,
            atLeast = figures.atLeast,
            expected = figures.expected,
            fees = figures.fees,
            countdown = figures.countdown,
            isExpired = isExpired,
            privacy = figures.privacy,
            primaryButton =
                ButtonState(
                    text = stringRes(if (isExpired) R.string.invest_review_refresh else R.string.invest_review_confirm),
                    isEnabled = !current.isBusy,
                    onClick = if (isExpired) ::onRefreshPrice else ::onConfirm,
                ),
            isBusy = current.isBusy,
            errorText = current.error,
            onDismiss = ::onDismissReview,
        )
    }

    private fun onRefreshPrice() {
        val current = review.value ?: return
        if (current.isBusy) return
        review.update { it?.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            investCatching { investRepository.prepareBuy(asset, current.prepared.usdAmountRequested()) }
                .onSuccess(::openReview)
                .onFailure { e ->
                    Twig.warn(e) { "InvestBuyVM: refreshing the price failed" }
                    review.update { it?.copy(isBusy = false, error = e.toInvestMessage()) }
                }
        }
    }

    private fun onConfirm() {
        val current = review.value ?: return
        if (current.isBusy || current.remainingSeconds <= 0) return
        // Busy until executeBuy returns: a second call for the same quote is refused ("already being paid").
        review.update { it?.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            // Keeps this screen (and this coroutine) alive while a Keystone signs over the QR screen.
            keystoneProposalRepository.signReturnRoute = InvestBuyArgs::class
            val result =
                try {
                    investCatching { investRepository.executeBuy(current.prepared) }
                } finally {
                    keystoneProposalRepository.signReturnRoute = null
                }
            result
                .onSuccess { depositAddress ->
                    closeReview()
                    navigationRouter.replace(
                        InvestProgressArgs(
                            depositAddress = depositAddress,
                            assetId = asset.assetId,
                            usdAmount = amount.value.amount?.toPlainString(),
                        ),
                    )
                }.onFailure { e ->
                    Twig.warn(e) { "InvestBuyVM: executeBuy did not complete" }
                    val cancelled = e is BiometricsCancelledException || e is BridgeAuthorizationCancelledException
                    review.update {
                        it?.copy(
                            isBusy = false,
                            error = if (cancelled) null else stringRes(R.string.invest_review_failed),
                        )
                    }
                }
        }
    }

    private fun onDismissReview() {
        if (review.value?.isBusy == true) return
        closeReview()
    }

    private fun closeReview() {
        countdownJob?.cancel()
        countdownJob = null
        review.update { null }
    }

    // The amount the user asked for is what a refreshed quote is for; the field can't change under the sheet.
    private fun PreparedBuy.usdAmountRequested(): BigDecimal = amount.value.amount ?: usdOut

    companion object {
        /** How long the amount has to sit still before it is worth a (Tor) round trip. */
        const val AMOUNT_SETTLE_DELAY_MS = 500L
        private const val COUNTDOWN_TICK_MS = 1_000L
        private val PRESETS_USD = listOf(BigDecimal(40), BigDecimal(100), BigDecimal(250))
        private val MAX_FEE_RESERVE_ZEC = BigDecimal("0.0005")
        private val MAX_HEADROOM = BigDecimal("0.98")
    }
}
