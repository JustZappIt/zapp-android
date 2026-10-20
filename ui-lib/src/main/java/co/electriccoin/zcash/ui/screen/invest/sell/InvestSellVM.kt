package co.electriccoin.zcash.ui.screen.invest.sell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.model.Holding
import co.electriccoin.zcash.ui.common.invest.model.InvestAssets
import co.electriccoin.zcash.ui.common.invest.model.PreparedSell
import co.electriccoin.zcash.ui.common.invest.model.SellAmount
import co.electriccoin.zcash.ui.common.invest.model.SellEstimate
import co.electriccoin.zcash.ui.common.invest.model.SellIntentRefusedException
import co.electriccoin.zcash.ui.common.invest.provider.InvestApiException
import co.electriccoin.zcash.ui.common.invest.repository.InvestRepository
import co.electriccoin.zcash.ui.common.invest.repository.InvestSellRepository
import co.electriccoin.zcash.ui.common.invest.repository.InvestTradeFollower
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.common.ExecuteFailure
import co.electriccoin.zcash.ui.screen.invest.common.InvestCurrency
import co.electriccoin.zcash.ui.screen.invest.common.InvestCurrencyProvider
import co.electriccoin.zcash.ui.screen.invest.common.InvestNoPriceState
import co.electriccoin.zcash.ui.screen.invest.common.InvestPresetState
import co.electriccoin.zcash.ui.screen.invest.common.InvestReviewCountdown
import co.electriccoin.zcash.ui.screen.invest.common.TradeBlock
import co.electriccoin.zcash.ui.screen.invest.common.investCatching
import co.electriccoin.zcash.ui.screen.invest.common.reopenText
import co.electriccoin.zcash.ui.screen.invest.common.toInvestMessage
import co.electriccoin.zcash.ui.screen.invest.common.toState
import co.electriccoin.zcash.ui.screen.invest.sellprogress.InvestSellProgressArgs
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
import kotlin.time.Clock
import kotlin.time.toJavaInstant

/**
 * The sell amount screen and its review sheet, which shows the intent the key would sign. Biometrics are asked for
 * inside [InvestSellRepository.executeSell]; an intent that isn't the reviewed transfer ends on "Something did not
 * match".
 */
@Suppress("TooManyFunctions")
internal class InvestSellVM(
    args: InvestSellArgs,
    private val investRepository: InvestRepository,
    private val sellRepository: InvestSellRepository,
    swapRepository: SwapRepository,
    currencyProvider: InvestCurrencyProvider,
    private val tradeFollower: InvestTradeFollower,
    private val navigationRouter: NavigationRouter,
    private val clock: Clock,
) : ViewModel() {
    private val asset = checkNotNull(InvestAssets.find(args.assetId)) { "Not a curated Invest asset" }

    private data class Form(
        val mode: SellAmountMode = SellAmountMode.MONEY,
        val amount: NumberTextFieldInnerState = NumberTextFieldInnerState(),
        /** Everything held, to the last base unit, whatever the field shows. */
        val sellAll: Boolean = false,
        val isRetrying: Boolean = false,
        val isPreparing: Boolean = false,
        val isRefused: Boolean = false,
    )

    private data class Review(
        val prepared: PreparedSell,
        val remainingSeconds: Long,
        val isBusy: Boolean = false,
        /** Another trade of the stock is pending, so Confirm stays off. */
        val isBlocked: Boolean = false,
        val error: StringResource? = null,
        val isSignedMessageOpen: Boolean = false,
    )

    private val form = MutableStateFlow(Form())
    private val quote = MutableStateFlow<SellQuote>(SellQuote.Idle)
    private val review = MutableStateFlow<Review?>(null)
    private val countdown = InvestReviewCountdown(viewModelScope, clock)

    private val currency =
        currencyProvider.observe().stateIn(viewModelScope, SharingStarted.Eagerly, InvestCurrency.USD)
    private val holding =
        investRepository.holdings
            .map { holdings -> holdings?.items?.firstOrNull { it.asset.assetId == asset.assetId } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val zecUsd =
        swapRepository.assets
            .map { it.zecAsset?.usdPrice }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                swapRepository.assets.value.zecAsset
                    ?.usdPrice
            )

    private val tradeBlock =
        investRepository.pendingTrades
            .map { TradeBlock.of(it, asset) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // collectLatest makes the leading delay a debounce. Keyed on what was typed and the currency, not the
        // exchange rate, which moves with every ZEC price tick.
        viewModelScope.launch {
            combine(form, currency.map { it.code }, ::estimateKeyOf)
                .distinctUntilChanged()
                .collectLatest { key ->
                    if (key == null) {
                        quote.update { SellQuote.Idle }
                        return@collectLatest
                    }
                    quote.update { SellQuote.Loading }
                    delay(AMOUNT_SETTLE_DELAY_MS)
                    requestEstimate()
                }
        }
    }

    val state: StateFlow<InvestSellState> =
        combine(form, quote, holding, currency, tradeBlock, ::buildState)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = buildState(form.value, quote.value, holding.value, currency.value, null),
            )

    val reviewState: StateFlow<InvestSellReviewState?> =
        combine(review, currency, zecUsd) { current, money, price -> current?.let { buildReview(it, money, price) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT), null)

    suspend fun followPendingTrades(): Nothing = tradeFollower.followPendingTrades()

    private fun sellAmountOf(
        current: Form,
        money: InvestCurrency = currency.value,
    ): SellAmount? {
        val typed = current.amount.amount?.takeIf { it.signum() > 0 }
        return when {
            current.sellAll -> SellAmount.All
            typed == null -> null
            current.mode == SellAmountMode.SHARES -> SellAmount.Units(typed)
            else -> SellAmount.Usd(money.toUsd(typed))
        }
    }

    /** What an estimate depends on, apart from the exchange rate; null when there's nothing to estimate. */
    private data class EstimateKey(
        val sellAll: Boolean,
        val mode: SellAmountMode,
        val typed: BigDecimal?,
        val currencyCode: String,
    )

    private fun estimateKeyOf(
        current: Form,
        currencyCode: String,
    ): EstimateKey? {
        val typed = current.amount.amount?.takeIf { it.signum() > 0 }
        if (!current.sellAll && typed == null) return null
        val mode = if (current.sellAll) SellAmountMode.MONEY else current.mode
        return EstimateKey(current.sellAll, mode, typed.takeUnless { current.sellAll }, currencyCode)
    }

    private suspend fun requestEstimate() {
        val key = estimateKeyOf(form.value, currency.value.code)
        val request = sellAmountOf(form.value)
        if (key == null || request == null) return
        val result = investCatching { sellRepository.estimateSell(asset, request) }
        // An estimate for an amount or currency that has since changed is worth nothing.
        if (estimateKeyOf(form.value, currency.value.code) != key) return
        quote.update {
            result.fold(
                onSuccess = { SellQuote.Ready(it) },
                onFailure = { e ->
                    if (e is InvestApiException.NoPrice) {
                        SellQuote.Ready(SellEstimate.NoPrice)
                    } else {
                        Twig.warn(e) { "InvestSellVM: estimate failed" }
                        SellQuote.Failed(e.toInvestMessage())
                    }
                },
            )
        }
    }

    private fun buildState(
        current: Form,
        currentQuote: SellQuote,
        held: Holding?,
        money: InvestCurrency,
        block: TradeBlock?,
    ): InvestSellState {
        val estimate = (currentQuote as? SellQuote.Ready)?.estimate
        val (notice, isDanger) = InvestSellPresenter.notice(currentQuote, asset, money)
        return InvestSellState(
            title = stringRes(R.string.invest_sell_title, asset.name),
            mode = current.mode,
            amountSymbol = if (current.mode == SellAmountMode.SHARES) asset.ticker else money.symbol,
            amountInput = NumberTextFieldState(innerState = current.amount, onValueChange = ::onAmountChange),
            holdingText = InvestSellPresenter.holdingText(held, asset, money),
            onModeChange = ::onModeChange,
            presets = presets(held, current.mode),
            ledger = InvestSellPresenter.ledger(currentQuote, asset, money),
            noPrice =
                if (estimate is SellEstimate.NoPrice) {
                    InvestNoPriceState(
                        body = InvestSellPresenter.noPriceBody(asset),
                        reopen = asset.reopenText(clock.now().toJavaInstant()),
                        isRetrying = current.isRetrying,
                        onTryAgain = ::onTryAgain,
                    )
                } else {
                    null
                },
            notice = notice,
            isNoticeDanger = isDanger,
            isAmountError =
                estimate is SellEstimate.ExceedsHolding ||
                    estimate is SellEstimate.NothingHeld ||
                    estimate is SellEstimate.TooSmallToSell,
            sellAllSuggestion =
                InvestSellPresenter
                    .sellAllSuggestion(estimate, held, current.sellAll, money)
                    ?.let { InvestSellAllSuggestion(it, ::onSellAll) },
            primaryButton =
                ButtonState(
                    text = stringRes(R.string.invest_review),
                    isEnabled = estimate is SellEstimate.Priced && !current.isPreparing && block == null,
                    isLoading = current.isPreparing,
                    onClick = ::onReview,
                ),
            isRefused = current.isRefused,
            tradeInProgress = block?.toState(asset, navigationRouter),
            onBack = navigationRouter::back,
        )
    }

    private fun presets(
        held: Holding?,
        mode: SellAmountMode,
    ): List<InvestPresetState> {
        val hasHolding = held != null && held.units.signum() > 0
        val canHalve = held != null && hasHolding && (mode == SellAmountMode.SHARES || held.usdValue != null)
        return listOf(
            InvestPresetState(stringRes(R.string.invest_sell_preset_half), isEnabled = canHalve, onClick = ::onHalf),
            InvestPresetState(
                label = stringRes(R.string.invest_sell_preset_all),
                isEnabled = hasHolding,
                onClick = ::onSellAll,
            ),
        )
    }

    private fun onAmountChange(next: NumberTextFieldInnerState) =
        form.update { it.copy(amount = next, sellAll = false, isRefused = false) }

    private fun onModeChange(mode: SellAmountMode) =
        form.update { current ->
            if (current.mode == mode) {
                current
            } else if (current.sellAll) {
                current.copy(mode = mode, amount = fullAmount(mode))
            } else {
                current.copy(mode = mode, amount = NumberTextFieldInnerState())
            }
        }

    // Reads the mode when tapped, so a tap right after switching halves in the mode now showing.
    private fun onHalf() =
        form.update { current ->
            InvestSellPresenter
                .share(holding.value, current.mode, currency.value, InvestSellPresenter.HALF)
                ?.let { current.copy(amount = NumberTextFieldInnerState.fromAmount(it), sellAll = false) }
                ?: current
        }

    private fun onSellAll() = form.update { it.copy(amount = fullAmount(it.mode), sellAll = true) }

    private fun fullAmount(mode: SellAmountMode): NumberTextFieldInnerState =
        InvestSellPresenter
            .share(holding.value, mode, currency.value, BigDecimal.ONE)
            ?.let(NumberTextFieldInnerState::fromAmount) ?: NumberTextFieldInnerState()

    private fun onTryAgain() {
        if (sellAmountOf(form.value) == null) return
        if (form.value.isRetrying) return
        form.update { it.copy(isRetrying = true) }
        viewModelScope.launch {
            requestEstimate()
            form.update { it.copy(isRetrying = false) }
        }
    }

    private fun onReview() {
        val request = sellAmountOf(form.value) ?: return
        // Set before launching, so a second tap while the first is still preparing does nothing.
        if (form.value.isPreparing || tradeBlock.value != null) return
        form.update { it.copy(isPreparing = true) }
        viewModelScope.launch {
            investCatching { sellRepository.prepareSell(asset, request) }
                .onSuccess(::openReview)
                .onFailure(::onPrepareFailed)
            form.update { it.copy(isPreparing = false) }
        }
    }

    private fun onPrepareFailed(e: Throwable) {
        Twig.warn(e) { "InvestSellVM: prepareSell failed" }
        when (e) {
            is SellIntentRefusedException -> form.update { it.copy(isRefused = true) }
            is InvestApiException.NoPrice -> quote.update { SellQuote.Ready(SellEstimate.NoPrice) }
            else -> quote.update { SellQuote.Failed(e.toInvestMessage()) }
        }
    }

    private fun openReview(prepared: PreparedSell) {
        review.update { Review(prepared, countdown.remainingSeconds(prepared.expiresAt)) }
        countdown.start(prepared.expiresAt) { remaining -> review.update { it?.copy(remainingSeconds = remaining) } }
    }

    private fun buildReview(
        current: Review,
        money: InvestCurrency,
        zecPrice: BigDecimal?,
    ): InvestSellReviewState {
        val figures = InvestSellPresenter.review(current.prepared, current.remainingSeconds, money, zecPrice)
        val isExpired = current.remainingSeconds <= 0
        return InvestSellReviewState(
            authorisation = figures.authorisation,
            atLeast = figures.atLeast,
            expected = figures.expected,
            fees = figures.fees,
            countdown = figures.countdown,
            isExpired = isExpired,
            signedMessage = current.prepared.signedMessage,
            isSignedMessageOpen = current.isSignedMessageOpen,
            onToggleSignedMessage = { review.update { it?.copy(isSignedMessageOpen = !it.isSignedMessageOpen) } },
            primaryButton =
                ButtonState(
                    text = stringRes(if (isExpired) R.string.invest_review_refresh else R.string.invest_sell_confirm),
                    isEnabled = !current.isBusy && (isExpired || !current.isBlocked),
                    onClick = if (isExpired) ::onRefreshPrice else ::onConfirm,
                ),
            isBusy = current.isBusy,
            errorText = current.error,
            onDismiss = ::onDismissReview,
        )
    }

    private fun onRefreshPrice() {
        if (review.value?.isBusy != false) return
        val request = sellAmountOf(form.value)
        if (request == null || closeIfBlocked()) return
        review.update { it?.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            investCatching { sellRepository.prepareSell(asset, request) }
                .onSuccess(::openReview)
                .onFailure { e ->
                    if (e is SellIntentRefusedException) {
                        closeReview()
                        form.update { it.copy(isRefused = true) }
                    } else {
                        Twig.warn(e) { "InvestSellVM: refreshing the price failed" }
                        review.update { it?.copy(isBusy = false, error = e.toInvestMessage()) }
                    }
                }
        }
    }

    private fun onConfirm() {
        val current = review.value?.takeUnless { it.isBusy || it.isBlocked } ?: return
        when {
            closeIfBlocked() -> Unit

            // The clock, not the last tick: the price may have lapsed since the sheet last redrew.
            countdown.isExpired(current.prepared.expiresAt) -> review.update { it?.copy(remainingSeconds = 0) }

            else -> execute(current)
        }
    }

    private fun execute(current: Review) {
        review.update { it?.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            investCatching { sellRepository.executeSell(current.prepared) }
                .onSuccess { depositAddress ->
                    closeReview()
                    navigationRouter.replace(progressArgs(depositAddress, current.prepared))
                }.onFailure { e -> onExecuteFailed(e, current) }
        }
    }

    private suspend fun onExecuteFailed(
        e: Throwable,
        current: Review,
    ) {
        Twig.warn(e) { "InvestSellVM: executeSell did not complete" }
        val failure =
            ExecuteFailure.classify(
                isCancelled = e is BiometricsCancelledException,
                isRefused = e is SellIntentRefusedException,
                isExpired = countdown.isExpired(current.prepared.expiresAt),
                ourDeposit = current.prepared.depositAddress,
                asset = asset,
                pendingTrades = investRepository.pendingTrades,
            )
        when (failure) {
            ExecuteFailure.Cancelled -> {
                review.update { it?.copy(isBusy = false) }
            }

            ExecuteFailure.RecordsUnreadable -> {
                closeReview()
            }

            is ExecuteFailure.OursPending -> {
                closeReview()
                navigationRouter.replace(progressArgs(failure.trade.depositAddress, current.prepared))
            }

            ExecuteFailure.Refused -> {
                closeReview()
                form.update { it.copy(isRefused = true) }
            }

            ExecuteFailure.Expired -> {
                review.update { it?.copy(isBusy = false, remainingSeconds = 0) }
            }

            ExecuteFailure.OtherTradePending -> {
                val inFlight = stringRes(R.string.invest_trade_in_flight, asset.name)
                review.update { it?.copy(isBusy = false, isBlocked = true, error = inFlight) }
            }

            ExecuteFailure.Failed -> {
                closeReview()
                quote.update { SellQuote.Failed(stringRes(R.string.invest_sell_review_failed)) }
            }
        }
    }

    private fun progressArgs(
        depositAddress: String,
        prepared: PreparedSell,
    ) = InvestSellProgressArgs(
        depositAddress = depositAddress,
        assetId = asset.assetId,
        usdAmount = prepared.usdIn.toPlainString(),
    )

    // A pending trade of this stock rules out another sale: the sheet gives way to the screen, which links to it.
    private fun closeIfBlocked(): Boolean {
        val isBlocked = tradeBlock.value != null
        if (isBlocked) closeReview()
        return isBlocked
    }

    private fun onDismissReview() {
        if (review.value?.isBusy == true) return
        closeReview()
    }

    private fun closeReview() {
        countdown.stop()
        review.update { null }
    }

    companion object {
        /** How long typing must pause before the amount is quoted. */
        const val AMOUNT_SETTLE_DELAY_MS = 500L
    }
}
