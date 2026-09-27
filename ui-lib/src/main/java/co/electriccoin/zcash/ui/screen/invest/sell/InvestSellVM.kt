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
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.buy.InvestBuyPresenter
import co.electriccoin.zcash.ui.screen.invest.buy.InvestNoPriceState
import co.electriccoin.zcash.ui.screen.invest.buy.InvestPresetState
import co.electriccoin.zcash.ui.screen.invest.common.InvestCurrency
import co.electriccoin.zcash.ui.screen.invest.common.InvestCurrencyProvider
import co.electriccoin.zcash.ui.screen.invest.common.InvestFormat
import co.electriccoin.zcash.ui.screen.invest.common.InvestPendingTrades
import co.electriccoin.zcash.ui.screen.invest.common.InvestTradeInProgressState
import co.electriccoin.zcash.ui.screen.invest.common.PendingTrade
import co.electriccoin.zcash.ui.screen.invest.common.investCatching
import co.electriccoin.zcash.ui.screen.invest.common.progressRoute
import co.electriccoin.zcash.ui.screen.invest.common.toInvestMessage
import co.electriccoin.zcash.ui.screen.invest.sellprogress.InvestSellProgressArgs
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
 * I8 and its review sheet I9. The amount is typed in the user's currency or in shares; a dry quote runs once
 * typing stops. "Review" fetches a live quote plus the intent the private-account key would sign, already checked
 * by the engine; the sheet shows that intent in words and holds it until it expires. Confirming asks for biometrics
 * inside [InvestSellRepository.executeSell]; a cancelled prompt leaves the user on the sheet, and an intent that
 * isn't the reviewed transfer ends on "Something did not match".
 */
@Suppress("TooManyFunctions")
internal class InvestSellVM(
    args: InvestSellArgs,
    private val investRepository: InvestRepository,
    private val sellRepository: InvestSellRepository,
    pendingTrades: InvestPendingTrades,
    currencyProvider: InvestCurrencyProvider,
    private val navigationRouter: NavigationRouter,
    private val clock: Clock,
) : ViewModel() {
    private val asset = checkNotNull(InvestAssets.find(args.assetId)) { "Not a curated Invest asset" }

    private data class Form(
        val mode: SellAmountMode = SellAmountMode.MONEY,
        val amount: NumberTextFieldInnerState = NumberTextFieldInnerState(),
        /** Sell everything held, to the last base unit, whatever the field shows. */
        val sellAll: Boolean = false,
        val isRetrying: Boolean = false,
        val isPreparing: Boolean = false,
        val isRefused: Boolean = false,
    )

    private data class Review(
        val prepared: PreparedSell,
        val remainingSeconds: Long,
        val isBusy: Boolean = false,
        val error: StringResource? = null,
        val isSignedMessageOpen: Boolean = false,
    )

    private val form = MutableStateFlow(Form())
    private val quote = MutableStateFlow<SellQuote>(SellQuote.Idle)
    private val review = MutableStateFlow<Review?>(null)
    private var countdownJob: Job? = null

    private val currency =
        currencyProvider.observe().stateIn(viewModelScope, SharingStarted.Eagerly, InvestCurrency.USD)
    private val holding =
        investRepository.holdings
            .map { holdings -> holdings?.items?.firstOrNull { it.asset.assetId == asset.assetId } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // The engine refuses a sale while a buy or sale of the same stock is still pending.
    private val tradeInFlight =
        pendingTrades
            .observe()
            .map { trades -> trades.firstOrNull { it.assetId == asset.assetId } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // collectLatest cancels the previous block, so the leading delay is the debounce: one quote per pause.
        viewModelScope.launch {
            form
                .map(::sellAmountOf)
                .distinctUntilChanged()
                .collectLatest { request ->
                    if (request == null) {
                        quote.update { SellQuote.Idle }
                        return@collectLatest
                    }
                    quote.update { SellQuote.Loading }
                    delay(AMOUNT_SETTLE_DELAY_MS)
                    requestEstimate(request)
                }
        }
    }

    val state: StateFlow<InvestSellState> =
        combine(form, quote, holding, currency, tradeInFlight, ::buildState)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = buildState(form.value, quote.value, holding.value, currency.value, null),
            )

    val reviewState: StateFlow<InvestSellReviewState?> =
        combine(review, currency) { current, money -> current?.let { buildReview(it, money) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT), null)

    private fun sellAmountOf(current: Form): SellAmount? {
        val typed = current.amount.amount?.takeIf { it.signum() > 0 }
        return when {
            current.sellAll -> SellAmount.All
            typed == null -> null
            current.mode == SellAmountMode.SHARES -> SellAmount.Units(typed)
            else -> SellAmount.Usd(currency.value.toUsd(typed))
        }
    }

    private suspend fun requestEstimate(request: SellAmount) {
        val result = investCatching { sellRepository.estimateSell(asset, request) }
        // A figure for an amount the user has already changed is worth nothing.
        if (sellAmountOf(form.value) != request) return
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
        inFlight: PendingTrade?,
    ): InvestSellState {
        val estimate = (currentQuote as? SellQuote.Ready)?.estimate
        val (notice, isDanger) = InvestSellPresenter.notice(currentQuote, asset, money)
        return InvestSellState(
            title = stringRes(R.string.invest_sell_title, asset.name),
            mode = current.mode,
            amountSymbol = if (current.mode == SellAmountMode.SHARES) asset.ticker else money.symbol,
            amountInput = NumberTextFieldState(innerState = current.amount, onValueChange = ::onAmountChange),
            holdingText = holdingText(held, money),
            onModeChange = ::onModeChange,
            presets = presets(held, current.mode),
            ledger = InvestSellPresenter.ledger(currentQuote, asset, money),
            noPrice =
                if (estimate is SellEstimate.NoPrice) {
                    InvestNoPriceState(
                        body = InvestSellPresenter.noPriceBody(asset),
                        reopen = InvestBuyPresenter.reopen(asset, clock.now().toJavaInstant()),
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
            sellAllSuggestion = sellAllSuggestion(estimate, held, current.sellAll, money),
            primaryButton =
                ButtonState(
                    text = stringRes(R.string.invest_buy_review),
                    isEnabled = estimate is SellEstimate.Priced && !current.isPreparing && inFlight == null,
                    onClick = ::onReview,
                ),
            isPreparing = current.isPreparing,
            isRefused = current.isRefused,
            tradeInProgress =
                inFlight?.let { trade ->
                    InvestTradeInProgressState(stringRes(R.string.invest_trade_in_flight, asset.name)) {
                        navigationRouter.forward(trade.progressRoute())
                    }
                },
            onBack = navigationRouter::back,
        )
    }

    private fun holdingText(
        held: Holding?,
        money: InvestCurrency,
    ): StringResource {
        val units = InvestFormat.units(held?.units ?: BigDecimal.ZERO, asset.ticker)
        val value = held?.usdValue ?: return stringRes(units)
        return stringRes(R.string.invest_buy_you_get_value_exact, money.format(value), units)
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

    // Offered only for a partial sale that would leave less than the minimum behind: selling that remainder later
    // would cost more in fixed fees than it is worth.
    private fun sellAllSuggestion(
        estimate: SellEstimate?,
        held: Holding?,
        sellAll: Boolean,
        money: InvestCurrency,
    ): InvestSellAllSuggestion? {
        val usdIn = (estimate as? SellEstimate.Priced)?.usdIn
        val left = held?.usdValue?.let { value -> usdIn?.let { value.subtract(it) } }
        val leavesTooLittle = left != null && left.signum() > 0 && left < InvestRepository.MINIMUM_USD
        return if (!sellAll && leavesTooLittle) {
            val minimum = money.formatPreset(money.presetFromUsd(InvestRepository.MINIMUM_USD))
            InvestSellAllSuggestion(stringRes(R.string.invest_sell_all_suggestion, minimum), ::onSellAll)
        } else {
            null
        }
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

    // Reads the mode and holding when tapped, not when the button was drawn, so a tap right after switching
    // between money and shares halves in the mode now showing.
    private fun onHalf() {
        val held = holding.value ?: return
        form.update { current ->
            val half =
                when (current.mode) {
                    SellAmountMode.SHARES -> {
                        held.units.divide(TWO, UNITS_SCALE, RoundingMode.DOWN)
                    }

                    SellAmountMode.MONEY -> {
                        held.usdValue?.let { currency.value.fromUsd(it).divide(TWO, 2, RoundingMode.DOWN) }
                    }
                } ?: return@update current
            current.copy(amount = NumberTextFieldInnerState.fromAmount(half), sellAll = false)
        }
    }

    private fun onSellAll() = form.update { it.copy(amount = fullAmount(it.mode), sellAll = true) }

    private fun fullAmount(mode: SellAmountMode): NumberTextFieldInnerState {
        val held = holding.value
        val full =
            when (mode) {
                SellAmountMode.SHARES -> held?.units?.setScale(UNITS_SCALE, RoundingMode.DOWN)
                SellAmountMode.MONEY -> held?.usdValue?.let(currency.value::fromUsd)?.setScale(2, RoundingMode.DOWN)
            }
        return full?.let(NumberTextFieldInnerState::fromAmount) ?: NumberTextFieldInnerState()
    }

    private fun onTryAgain() {
        val request = sellAmountOf(form.value) ?: return
        if (form.value.isRetrying) return
        form.update { it.copy(isRetrying = true) }
        viewModelScope.launch {
            requestEstimate(request)
            form.update { it.copy(isRetrying = false) }
        }
    }

    private fun onReview() {
        val request = sellAmountOf(form.value) ?: return
        if (form.value.isPreparing) return
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

    private fun remainingSeconds(prepared: PreparedSell): Long =
        (prepared.expiresAt - clock.now()).inWholeSeconds.coerceAtLeast(0)

    private fun buildReview(
        current: Review,
        money: InvestCurrency,
    ): InvestSellReviewState {
        val figures = InvestSellPresenter.review(current.prepared, current.remainingSeconds, money)
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
                    isEnabled = !current.isBusy,
                    onClick = if (isExpired) ::onRefreshPrice else ::onConfirm,
                ),
            isBusy = current.isBusy,
            errorText = current.error,
            onDismiss = ::onDismissReview,
        )
    }

    private fun onRefreshPrice() {
        val current = review.value
        val request = sellAmountOf(form.value)
        if (current == null || request == null || current.isBusy) return
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
        val current = review.value ?: return
        if (current.isBusy || current.remainingSeconds <= 0) return
        // Busy until executeSell returns: the engine refuses a second sale of the same stock meanwhile.
        review.update { it?.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            investCatching { sellRepository.executeSell(current.prepared) }
                .onSuccess { depositAddress ->
                    closeReview()
                    navigationRouter.replace(
                        InvestSellProgressArgs(
                            depositAddress = depositAddress,
                            assetId = asset.assetId,
                            usdAmount = current.prepared.usdIn.toPlainString(),
                        ),
                    )
                }.onFailure(::onExecuteFailed)
        }
    }

    private fun onExecuteFailed(e: Throwable) {
        Twig.warn(e) { "InvestSellVM: executeSell did not complete" }
        when (e) {
            is BiometricsCancelledException -> {
                review.update { it?.copy(isBusy = false) }
            }

            is SellIntentRefusedException -> {
                closeReview()
                form.update { it.copy(isRefused = true) }
            }

            else -> {
                review.update { it?.copy(isBusy = false, error = stringRes(R.string.invest_sell_review_failed)) }
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

    companion object {
        /** How long the amount has to sit still before it is worth a (Tor) round trip. */
        const val AMOUNT_SETTLE_DELAY_MS = 500L
        private const val COUNTDOWN_TICK_MS = 1_000L
        private const val UNITS_SCALE = 4
        private val TWO = BigDecimal(2)
    }
}
