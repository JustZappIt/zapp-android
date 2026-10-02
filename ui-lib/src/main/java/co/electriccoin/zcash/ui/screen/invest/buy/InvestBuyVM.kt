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
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettingsRepository
import co.electriccoin.zcash.ui.common.invest.repository.InvestTradeFollower
import co.electriccoin.zcash.ui.common.provider.BridgeAuthorizationCancelledException
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
import co.electriccoin.zcash.ui.screen.invest.common.InvestFormat
import co.electriccoin.zcash.ui.screen.invest.common.InvestTradeInProgressState
import co.electriccoin.zcash.ui.screen.invest.common.TradeBlock
import co.electriccoin.zcash.ui.screen.invest.common.investCatching
import co.electriccoin.zcash.ui.screen.invest.common.sellOnlyNotice
import co.electriccoin.zcash.ui.screen.invest.common.toInvestMessage
import co.electriccoin.zcash.ui.screen.invest.common.toState
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
 * I5 and its review sheet I6. The amount is typed in the user's currency and quoted in USD once typing has stopped
 * for [AMOUNT_SETTLE_DELAY_MS]. "Review" fetches a live quote the repository has checked against the request, and
 * the sheet holds it until [PreparedBuy.expiresAt]; after that the only way on is a fresh one.
 *
 * Confirming hands the quote to the repository, which runs the existing authenticated send. After a failure the
 * engine is asked first whether it recorded the buy: if so, ZEC may have gone out, so its progress screen opens and
 * nothing offers to pay again. Only an unrecorded failure is read as nothing sent (see [ExecuteFailure]).
 *
 * Where the saved country doesn't allow buying (sell-only), Review stays off and the sell-only note shows instead.
 */
@Suppress("TooManyFunctions")
internal class InvestBuyVM(
    args: InvestBuyArgs,
    private val investRepository: InvestRepository,
    settingsRepository: InvestSettingsRepository,
    accountDataSource: AccountDataSource,
    swapRepository: SwapRepository,
    currencyProvider: InvestCurrencyProvider,
    private val tradeFollower: InvestTradeFollower,
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
        /** The USD asked for at review, which the progress screen shows (not a recomputation at a later rate). */
        val usd: BigDecimal,
        val remainingSeconds: Long,
        val isBusy: Boolean = false,
        /** Refused because another trade of the stock is pending: nothing was sent, and Confirm stays off. */
        val isBlocked: Boolean = false,
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

    // Amounts are typed and shown in the user's currency; 1Click is asked in USD.
    private val currency =
        currencyProvider.observe().stateIn(viewModelScope, SharingStarted.Eagerly, InvestCurrency.USD)

    private val tradeBlock =
        investRepository.pendingTrades
            .map { TradeBlock.of(it, asset) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Null while buying is allowed; until the settings are read, buying waits too. */
    private val notOffered =
        settingsRepository.settings
            .map { settings ->
                if (settings.isAvailable) null else NotOffered(settings.sellOnlyNotice(navigationRouter))
            }.stateIn(viewModelScope, SharingStarted.Eagerly, NotOffered(null))

    private data class NotOffered(
        val notice: InvestTradeInProgressState?,
    )

    init {
        // collectLatest cancels the previous block, so the leading delay is the debounce: one quote per pause.
        // The currency is part of the key, so a new exchange rate re-asks for the same typed amount.
        viewModelScope.launch {
            combine(amount.map { it.amount }, currency) { local, money -> local?.let(money::toUsd) }
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
        combine(
            amount,
            quote,
            combine(isRetrying, isPreparing, tradeBlock, notOffered) { retrying, preparing, block, notHere ->
                Flags(retrying, preparing, block, notHere)
            },
            combine(spendableZec, zecUsd, ::Wallet),
            currency,
        ) { amt, current, flags, wallet, money ->
            buildState(amt, current, flags, wallet, money)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue =
                buildState(
                    amount.value,
                    quote.value,
                    Flags(retrying = false, preparing = false, block = null, notOffered = notOffered.value),
                    Wallet(spendableZec.value, zecUsd.value),
                    currency.value,
                ),
        )

    private data class Wallet(
        val spendable: Zatoshi,
        val zecUsd: BigDecimal?,
    )

    private data class Flags(
        val retrying: Boolean,
        val preparing: Boolean,
        val block: TradeBlock?,
        val notOffered: NotOffered?,
    )

    val reviewState: StateFlow<InvestReviewState?> =
        combine(review, currency) { current, money -> current?.let { buildReview(it, money) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT), null)

    /**
     * Follows pending trades while this screen is STARTED, so a stock held back by one unblocks while the user waits
     * here. The follower is shared, so this adds no second poller.
     */
    suspend fun followPendingTrades(): Nothing = tradeFollower.followPendingTrades()

    private fun usdOf(local: BigDecimal): BigDecimal = currency.value.toUsd(local)

    private suspend fun requestEstimate(usd: BigDecimal) {
        if (usd < InvestRepository.MINIMUM_USD) {
            quote.update { BuyQuote.Ready(BuyEstimate.BelowMinimum(InvestRepository.MINIMUM_USD)) }
            return
        }
        val result = investCatching { investRepository.estimateBuy(asset, usd) }
        // A figure for an amount the user has already changed is worth nothing.
        if (amount.value.amount?.let(::usdOf) != usd) return
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
        flags: Flags,
        wallet: Wallet,
        money: InvestCurrency,
    ): InvestBuyState {
        val estimate = (current as? BuyQuote.Ready)?.estimate
        val (notice, isDanger) = InvestBuyPresenter.notice(current, money)
        return InvestBuyState(
            title = stringRes(R.string.invest_buy_title, asset.name),
            currencySymbol = money.symbol,
            amountInput = NumberTextFieldState(innerState = amt, onValueChange = ::onAmountChange),
            balanceText = balanceText(wallet, money),
            presets = presets(maxUsd(wallet.spendable, wallet.zecUsd), money),
            ledger = InvestBuyPresenter.ledger(current, asset, amt.amount?.let(money::toUsd), money),
            noPrice =
                if (estimate is BuyEstimate.NoPrice) {
                    InvestNoPriceState(
                        body = InvestBuyPresenter.noPriceBody(asset),
                        reopen = InvestBuyPresenter.reopen(asset, clock.now().toJavaInstant()),
                        isRetrying = flags.retrying,
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
                    isEnabled =
                        estimate is BuyEstimate.Priced &&
                            !flags.preparing &&
                            flags.block == null &&
                            flags.notOffered == null,
                    onClick = ::onReview,
                ),
            isPreparing = flags.preparing,
            tradeInProgress = flags.block?.toState(asset, navigationRouter),
            sellOnly = flags.notOffered?.notice,
            onBack = navigationRouter::back,
        )
    }

    private fun balanceText(
        wallet: Wallet,
        money: InvestCurrency,
    ): StringResource {
        val zec = wallet.spendable.convertZatoshiToZec()
        val zecText = InvestFormat.zec(zec)
        return if (wallet.zecUsd == null) {
            stringRes(zecText)
        } else {
            stringRes(R.string.invest_buy_balance_value, zecText, money.format(zec.multiply(wallet.zecUsd)))
        }
    }

    // $40, $100 and $250 in the user's currency, rounded up to a round figure so the smallest still clears $40.
    private fun presets(
        maxUsd: BigDecimal?,
        money: InvestCurrency,
    ): List<InvestPresetState> =
        PRESETS_USD.map { usd ->
            val local = money.presetFromUsd(usd)
            InvestPresetState(stringRes(money.formatPreset(local)), isEnabled = true) { setAmount(local) }
        } +
            InvestPresetState(stringRes(R.string.invest_buy_preset_max), isEnabled = maxUsd != null) {
                maxUsd?.let { setAmount(money.fromUsd(it).setScale(2, RoundingMode.DOWN)) }
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

    private fun setAmount(local: BigDecimal) = amount.update { NumberTextFieldInnerState.fromAmount(local) }

    private fun onAmountChange(next: NumberTextFieldInnerState) = amount.update { next }

    private fun onTryAgain() {
        val usd = amount.value.amount?.let(::usdOf) ?: return
        if (isRetrying.value) return
        isRetrying.update { true }
        viewModelScope.launch {
            requestEstimate(usd)
            isRetrying.update { false }
        }
    }

    private fun onReview() {
        val usd = amount.value.amount?.let(::usdOf) ?: return
        // Set before launching, so a second tap while the first is still preparing does nothing.
        if (isPreparing.value || tradeBlock.value != null || notOffered.value != null) return
        isPreparing.update { true }
        viewModelScope.launch {
            investCatching { investRepository.prepareBuy(asset, usd) }
                .onSuccess { openReview(it, usd) }
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

    private fun openReview(
        prepared: PreparedBuy,
        usd: BigDecimal,
    ) {
        review.update { Review(prepared, usd, remainingSeconds(prepared)) }
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

    private fun isExpired(prepared: PreparedBuy): Boolean = clock.now() >= prepared.expiresAt

    private fun buildReview(
        current: Review,
        money: InvestCurrency,
    ): InvestReviewState {
        val figures = InvestBuyPresenter.review(current.prepared, current.remainingSeconds, money)
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
                    isEnabled = !current.isBusy && (isExpired || !current.isBlocked),
                    onClick = if (isExpired) ::onRefreshPrice else ::onConfirm,
                ),
            isBusy = current.isBusy,
            errorText = current.error,
            onDismiss = ::onDismissReview,
        )
    }

    private fun onRefreshPrice() {
        val current = review.value
        if (current == null || current.isBusy || closeIfBlocked()) return
        review.update { it?.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            investCatching { investRepository.prepareBuy(asset, current.usd) }
                .onSuccess { openReview(it, current.usd) }
                .onFailure { e ->
                    Twig.warn(e) { "InvestBuyVM: refreshing the price failed" }
                    review.update { it?.copy(isBusy = false, error = e.toInvestMessage()) }
                }
        }
    }

    private fun onConfirm() {
        val current = review.value?.takeUnless { it.isBusy || it.isBlocked } ?: return
        when {
            closeIfBlocked() -> Unit

            // The clock, not the last countdown tick: the price may have lapsed since the sheet last redrew.
            isExpired(current.prepared) -> review.update { it?.copy(remainingSeconds = 0) }

            else -> execute(current)
        }
    }

    private fun execute(current: Review) {
        // Busy until executeBuy returns: a second tap does nothing, and the engine refuses a second payment anyway.
        review.update { it?.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            investCatching { investRepository.executeBuy(current.prepared) }
                .onSuccess { depositAddress ->
                    closeReview()
                    navigationRouter.replace(progressArgs(depositAddress, current.usd))
                }.onFailure { e -> onExecuteFailed(e, current) }
        }
    }

    private suspend fun onExecuteFailed(
        e: Throwable,
        current: Review,
    ) {
        Twig.warn(e) { "InvestBuyVM: executeBuy did not complete" }
        val failure =
            ExecuteFailure.classify(
                isCancelled = e is BridgeAuthorizationCancelledException || e is BiometricsCancelledException,
                isRefused = false,
                isExpired = isExpired(current.prepared),
                ourDeposit = current.prepared.quote.depositAddress.address,
                asset = asset,
                pendingTrades = investRepository.pendingTrades,
            )
        when (failure) {
            ExecuteFailure.Cancelled -> {
                review.update { it?.copy(isBusy = false) }
            }

            // Unknown whether it went out: the screen's unreadable-records note (and its support link) takes over.
            ExecuteFailure.RecordsUnreadable -> {
                closeReview()
            }

            is ExecuteFailure.OursPending -> {
                closeReview()
                navigationRouter.replace(progressArgs(failure.trade.depositAddress, current.usd))
            }

            ExecuteFailure.Expired -> {
                review.update { it?.copy(isBusy = false, remainingSeconds = 0) }
            }

            ExecuteFailure.OtherTradePending -> {
                review.update {
                    it?.copy(
                        isBusy = false,
                        isBlocked = true,
                        error = stringRes(R.string.invest_trade_in_flight, asset.name),
                    )
                }
            }

            ExecuteFailure.Refused, ExecuteFailure.Failed -> {
                closeReview()
                quote.update { BuyQuote.Failed(stringRes(R.string.invest_review_failed)) }
            }
        }
    }

    private fun progressArgs(
        depositAddress: String,
        usd: BigDecimal,
    ) = InvestProgressArgs(depositAddress = depositAddress, assetId = asset.assetId, usdAmount = usd.toPlainString())

    // A pending trade of this stock (a buy that may already have been paid, say) rules out paying again: the sheet
    // gives way to the screen, which names that trade and links to it.
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
        countdownJob?.cancel()
        countdownJob = null
        review.update { null }
    }

    companion object {
        /** How long the amount has to sit still before it is worth a (Tor) round trip. */
        const val AMOUNT_SETTLE_DELAY_MS = 500L
        private const val COUNTDOWN_TICK_MS = 1_000L
        private val PRESETS_USD = listOf(BigDecimal(40), BigDecimal(100), BigDecimal(250))
        private val MAX_FEE_RESERVE_ZEC = BigDecimal("0.0005")
        private val MAX_HEADROOM = BigDecimal("0.98")
    }
}
