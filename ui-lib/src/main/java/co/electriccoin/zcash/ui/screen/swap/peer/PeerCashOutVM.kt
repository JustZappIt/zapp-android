package co.electriccoin.zcash.ui.screen.swap.peer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.provider.PeerPayeeHandleProvider
import co.electriccoin.zcash.ui.common.repository.BaseBalance
import co.electriccoin.zcash.ui.common.repository.BaseBalanceRepository
import co.electriccoin.zcash.ui.common.repository.PeerCashOutRepository
import co.electriccoin.zcash.ui.common.repository.PeerCashOutRun
import co.electriccoin.zcash.ui.common.usecase.GetPeerActiveOrdersUseCase
import co.electriccoin.zcash.ui.common.usecase.GetPeerMarketSnapshotUseCase
import co.electriccoin.zcash.ui.common.usecase.ObservePeerCommittedUsdcUseCase
import co.electriccoin.zcash.ui.common.usecase.ReconcilePeerCheckpointsUseCase
import co.electriccoin.zcash.ui.common.usecase.StartPeerCashOutUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.TextFieldState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.swap.peer.order.PeerOrderArgs
import co.electriccoin.zcash.ui.screen.swap.peer.progress.PeerCashOutProgressArgs
import co.electriccoin.zcash.ui.screen.swap.upi.bridge.BridgeToBaseArgs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.justzappit.evm.math.DecimalRounding
import xyz.justzappit.evm.math.decimalMultiply
import xyz.justzappit.evm.math.decimalSetScale
import xyz.justzappit.evm.math.decimalToPlainString
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.peer.PayeeHandle
import xyz.justzappit.offramp.peer.PeerCashOutRequest
import xyz.justzappit.offramp.peer.PeerCurrency
import xyz.justzappit.offramp.peer.PeerCurrencySelection
import xyz.justzappit.offramp.peer.PeerMarketSnapshot
import xyz.justzappit.offramp.peer.PeerNetworks
import xyz.justzappit.offramp.peer.PeerOracleRate
import xyz.justzappit.offramp.peer.PeerOrderSnapshot
import xyz.justzappit.offramp.peer.PeerPlatform
import xyz.justzappit.offramp.peer.PeerRateQuote
import xyz.justzappit.offramp.peer.PeerSpendable
import java.math.BigDecimal

@Suppress("TooManyFunctions")
internal class PeerCashOutVM(
    private val navigationRouter: NavigationRouter,
    private val baseBalance: BaseBalanceRepository,
    private val payeeHandleProvider: PeerPayeeHandleProvider,
    private val oracleRate: PeerOracleRate,
    private val getMarketSnapshot: GetPeerMarketSnapshotUseCase,
    private val getActiveOrders: GetPeerActiveOrdersUseCase,
    private val observeCommitted: ObservePeerCommittedUsdcUseCase,
    private val reconcileCheckpoints: ReconcilePeerCheckpointsUseCase,
    private val startCashOut: StartPeerCashOutUseCase,
    private val repository: PeerCashOutRepository,
    private val platform: PeerPlatform,
) : ViewModel() {
    private val amountState = MutableStateFlow(NumberTextFieldInnerState())
    private val handle = MutableStateFlow("")
    private val selection = MutableStateFlow(PeerCurrencySelection.of(platform.defaultCurrencies))
    private val market = MutableStateFlow<PeerMarketSnapshot?>(null)
    private val quote = MutableStateFlow<PeerRateQuote?>(null)
    private val chainOrders = MutableStateFlow<List<PeerOrderSnapshot>>(emptyList())

    /**
     * Claimed on the tapping thread. The reservation a start records is only observable a frame or
     * two later, so two taps would both pass the same rendered balance check and open two cash-outs
     * against one balance.
     */
    private val isSubmitting = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            payeeHandleProvider.get(platform)?.let { record -> handle.update { record.handle.value } }
        }
        viewModelScope.launch { refresh() }
        // Re-read once an attempt settles: its amount has left the account and its order has become
        // something the indexer can answer for.
        viewModelScope.launch {
            repository.runs
                .map { runs -> runs.count { it.holdsFunds } }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    baseBalance.invalidate()
                    refresh()
                }
        }
        viewModelScope.launch {
            selection.map { it.primary }.distinctUntilChanged().collectLatest(::refreshMarketAndRate)
        }
    }

    val state: StateFlow<PeerCashOutState> =
        combine(
            combine(amountState, isSubmitting, ::AmountEntry),
            handle,
            selection,
            combine(baseBalance.balance, observeCommitted()) { read, committed ->
                when (read) {
                    BaseBalance.Loading -> PeerSpendable.Loading
                    BaseBalance.Unavailable -> PeerSpendable.Unavailable
                    is BaseBalance.Loaded -> PeerSpendable.Ready(read.balance, committed)
                }
            },
            combine(market, quote, repository.runs, chainOrders, ::Orders),
        ) { entry, typedHandle, currencies, spendable, orders ->
            buildState(entry, typedHandle, currencies, spendable, orders)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue =
                buildState(
                    entry = AmountEntry(NumberTextFieldInnerState(), isSubmitting = false),
                    typedHandle = "",
                    currencies = PeerCurrencySelection.of(platform.defaultCurrencies),
                    spendable = PeerSpendable.Loading,
                    orders = Orders(null, null, emptyList(), emptyList()),
                ),
        )

    private fun buildState(
        entry: AmountEntry,
        typedHandle: String,
        currencies: PeerCurrencySelection,
        spendable: PeerSpendable,
        orders: Orders,
    ): PeerCashOutState {
        val amount = entry.amount
        val usdc = amount.amount?.takeIf { it > BigDecimal.ZERO }?.let(Usdc6::ofWhole)
        val amountError = amountErrorFor(usdc, spendable)
        val handleError = handleErrorFor(typedHandle)
        return PeerCashOutState(
            platform = platform,
            title = stringRes(R.string.peer_offramp_title, platform.displayName()),
            // explicitError would print the message against the number; the notice below says it.
            amountInput =
                NumberTextFieldState(
                    innerState = amount,
                    onValueChange = { next -> amountState.update { next } },
                ),
            amountError = amountError,
            availableBalance = availableBalanceFor(spendable),
            fiatEquivalent = fiatEquivalentOf(usdc, orders.quote),
            ledger = ledgerRows(orders, currencies, spendable),
            notice = amountError ?: sizingWarningFor(usdc, orders.market),
            isNoticeDanger = amountError != null,
            topUpButton =
                ButtonState(
                    text = stringRes(R.string.peer_offramp_top_up_button),
                    onClick = ::onTopUpClick,
                ),
            handleField =
                TextFieldState(
                    value = stringRes(typedHandle),
                    error = handleError,
                    onValueChange = { next -> handle.update { next } },
                ),
            handleHint = platform.handleHint(),
            handleNormalized = normalizedEchoFor(typedHandle),
            handleUnverified = unverifiedNoticeFor(typedHandle),
            currencies =
                platform.currencies.map { currency ->
                    PeerCurrencyChipState(
                        currency = currency,
                        isSelected = currency in currencies,
                        isToggleable = platform.offersCurrencyChoice,
                        onClick = { onCurrencyToggle(currency) },
                    )
                },
            activeOrders = orderRows(orders),
            primaryButton =
                ButtonState(
                    text = stringRes(R.string.peer_offramp_continue),
                    isEnabled =
                        usdc != null &&
                            amountError == null &&
                            handleError == null &&
                            typedHandle.isNotBlank() &&
                            spendable is PeerSpendable.Ready &&
                            !entry.isSubmitting,
                    onClick = ::onContinueClick,
                ),
            onBack = navigationRouter::back,
        )
    }

    /**
     * Over the spendable balance is an inline stop, not a silent ZEC bridge. Anything already
     * promised to an unfinished attempt is subtracted first, so three orders cannot share one
     * balance, and a balance we could not read blocks rather than waves through.
     */
    private fun amountErrorFor(usdc: Usdc6?, spendable: PeerSpendable): StringResource? {
        val floor = Usdc6.ofMicros(PeerNetworks.RECOMMENDED_MIN_CASHOUT_MICROS)
        return when {
            usdc == null -> {
                null
            }

            usdc < floor -> {
                stringRes(R.string.peer_offramp_error_below_minimum, floor.toDisplayString(true))
            }

            spendable is PeerSpendable.Unavailable -> {
                stringRes(R.string.peer_offramp_error_balance_unavailable)
            }

            spendable is PeerSpendable.Ready && !spendable.covers(usdc) -> {
                stringRes(
                    R.string.peer_offramp_error_above_available,
                    spendable.available.toDisplayString(stripTrailingZeros = true),
                )
            }

            else -> {
                null
            }
        }
    }

    // Unitless: it sits beside the field's own USDC symbol, and repeating it there reads as noise.
    private fun availableBalanceFor(spendable: PeerSpendable): StringResource =
        when (spendable) {
            PeerSpendable.Loading -> stringRes(R.string.peer_offramp_balance_pending)
            PeerSpendable.Unavailable -> stringRes(R.string.peer_offramp_balance_pending)
            is PeerSpendable.Ready -> stringRes(spendable.available.toDisplayString(stripTrailingZeros = true))
        }

    private fun handleErrorFor(typed: String): StringResource? {
        if (typed.isBlank()) return null
        return if (acceptedHandleOf(typed) == null) {
            stringRes(R.string.peer_offramp_error_handle_format)
        } else {
            null
        }
    }

    // Chime repairs an unprefixed ChimeSign to `$handle`, and a buyer pays what was registered.
    private fun normalizedEchoFor(typed: String): StringResource? =
        acceptedHandleOf(typed)
            ?.value
            ?.takeIf { it != typed.trim() }
            ?.let { stringRes(R.string.peer_offramp_handle_registers_as, it) }

    private fun unverifiedNoticeFor(typed: String): StringResource? =
        acceptedHandleOf(typed)
            ?.takeIf { !platform.validatesHandleLive }
            ?.let { stringRes(R.string.peer_offramp_handle_unverified) }

    private fun acceptedHandleOf(typed: String): PayeeHandle? =
        runCatching { platform.normalizeHandle(typed) }
            .getOrNull()
            ?.takeIf { platform.hasPlausibleFormat(it) }

    /**
     * The rate, the wait and what is spendable, as labelled rows in the same ledger the UPI offramp
     * uses. The long-form explanation of what "indicative" means lives in the info sheet, not in a
     * paragraph the user has to read past on every visit.
     */
    private fun ledgerRows(
        orders: Orders,
        currencies: PeerCurrencySelection,
        spendable: PeerSpendable,
    ): List<PeerLedgerRow> =
        buildList {
            add(
                PeerLedgerRow(
                    label = stringRes(R.string.peer_offramp_ledger_rate),
                    value =
                        orders.quote?.let {
                            stringRes(
                                R.string.peer_offramp_rate_value,
                                decimalToPlainString(it.fiatPerUsdc),
                                it.currency.code,
                            )
                        } ?: stringRes(R.string.peer_offramp_rate_unavailable),
                ),
            )
            // Available lives in the amount field; this row only explains why it is lower than the
            // account balance.
            (spendable as? PeerSpendable.Ready)?.takeIf { it.hasCommitment }?.let {
                add(
                    PeerLedgerRow(
                        label = stringRes(R.string.peer_offramp_ledger_in_progress),
                        value = usdcAmount(it.committed),
                    ),
                )
            }
            add(
                PeerLedgerRow(
                    label = stringRes(R.string.peer_offramp_ledger_paid_to),
                    value = platform.displayName(),
                ),
            )
            if (currencies.additional.isNotEmpty()) {
                add(
                    PeerLedgerRow(
                        label = stringRes(R.string.peer_offramp_ledger_currencies),
                        value = stringRes(currencies.all.joinToString(CURRENCY_SEPARATOR) { it.code }),
                    ),
                )
            }
        }

    private fun fiatEquivalentOf(usdc: Usdc6?, quote: PeerRateQuote?): StringResource? {
        if (usdc == null || quote == null) return null
        val fiat = decimalMultiply(usdc.whole, quote.fiatPerUsdc)
        return stringRes(
            R.string.peer_offramp_hero_secondary,
            decimalToPlainString(decimalSetScale(fiat, quote.currency.precision, DecimalRounding.HALF_UP)),
            quote.currency.code,
        )
    }

    private fun sizingWarningFor(usdc: Usdc6?, market: PeerMarketSnapshot?): StringResource? =
        usdc
            ?.takeIf { market?.isOversized(it) == true }
            ?.let {
                stringRes(
                    R.string.peer_offramp_sizing_warning,
                    market?.averageFill?.toDisplayString(stripTrailingZeros = true).orEmpty(),
                )
            }

    private fun usdcAmount(amount: Usdc6): StringResource =
        stringRes(R.string.peer_offramp_usdc_amount, amount.toDisplayString(stripTrailingZeros = true))

    private fun orderRows(orders: Orders): List<PeerActiveOrderState> =
        orders.runs.filter { it.isUnindexed }.map(::inFlightRow) +
            orders.chain.map(::chainRow)

    private fun inFlightRow(run: PeerCashOutRun): PeerActiveOrderState =
        PeerActiveOrderState(
            key = run.id.value,
            title = run.rowTitle(),
            subtitle = run.rowSubtitle(),
            onClick = { navigationRouter.forward(PeerCashOutProgressArgs(cashOutId = run.id.value)) },
        )

    private fun chainRow(snapshot: PeerOrderSnapshot): PeerActiveOrderState =
        PeerActiveOrderState(
            key = snapshot.id.composite,
            title = snapshot.rowTitle(),
            subtitle = snapshot.rowSubtitle(),
            onClick = { navigationRouter.forward(PeerOrderArgs(depositIdComposite = snapshot.id.composite)) },
        )

    private fun onCurrencyToggle(currency: PeerCurrency) {
        if (!platform.offersCurrencyChoice) return
        selection.update { it.toggle(currency) }
    }

    private fun onTopUpClick() = navigationRouter.forward(BridgeToBaseArgs())

    private fun onContinueClick() {
        val amount =
            amountState.value.amount
                ?.takeIf { it > BigDecimal.ZERO }
                ?.let(Usdc6::ofWhole)
        val normalized = runCatching { platform.normalizeHandle(handle.value) }.getOrNull()
        if (amount == null || normalized == null) return
        if (!isSubmitting.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                submit(amount, normalized)
            } finally {
                isSubmitting.update { false }
            }
        }
    }

    private suspend fun submit(amount: Usdc6, normalized: PayeeHandle) {
        // The registered hash survives only while the handle is unchanged; editing it forces a
        // fresh registration.
        val existing = payeeHandleProvider.get(platform)
        val hash = existing?.hash?.takeIf { existing.handle == normalized }
        payeeHandleProvider.store(platform = platform, handle = normalized, hash = hash)
        val request =
            runCatching {
                PeerCashOutRequest(
                    platform = platform,
                    handle = normalized,
                    currencies = selection.value.all,
                    amount = amount,
                    cachedPayeeHash = hash,
                )
            }.onFailure { Twig.warn(it) { "PeerCashOutVM: rejected request" } }
                .getOrNull() ?: return
        val id = startCashOut(request)
        amountState.update { NumberTextFieldInnerState() }
        navigationRouter.forward(PeerCashOutProgressArgs(cashOutId = id.value))
    }

    private suspend fun refresh() {
        reconcileCheckpoints()
        chainOrders.update { getActiveOrders() }
    }

    /** Cleared before the read, so a failed one never leaves the previous currency's number on screen. */
    private suspend fun refreshMarketAndRate(currency: PeerCurrency) {
        market.update { null }
        quote.update { null }
        market.update { getMarketSnapshot(platform, currency) }
        quote.update { oracleRate.quote(currency, System.currentTimeMillis() / MILLIS_PER_SECOND) }
    }

    private data class AmountEntry(
        val amount: NumberTextFieldInnerState,
        val isSubmitting: Boolean,
    )

    private data class Orders(
        val market: PeerMarketSnapshot?,
        val quote: PeerRateQuote?,
        val runs: List<PeerCashOutRun>,
        val chain: List<PeerOrderSnapshot>,
    )

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val CURRENCY_SEPARATOR = ", "
    }
}
