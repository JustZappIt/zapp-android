package co.electriccoin.zcash.ui.screen.swap.upi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.bestEffort
import co.electriccoin.zcash.ui.common.provider.OfframpCheckpointStorageProvider
import co.electriccoin.zcash.ui.common.provider.StoreCorruptedException
import co.electriccoin.zcash.ui.common.repository.BaseBalanceRepository
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.zapp.ZappConfirmationState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.settings.p2p.P2pTransactionsArgs
import co.electriccoin.zcash.ui.screen.swap.upi.bridge.BridgeToBaseArgs
import co.electriccoin.zcash.ui.screen.swap.upi.progress.UpiOfframpProgressArgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.offramp.config.P2pNetworkConfig
import xyz.justzappit.offramp.config.P2pNetworks
import xyz.justzappit.offramp.orchestrator.MerchantAvailability
import xyz.justzappit.offramp.orchestrator.OfframpCheckpoint
import xyz.justzappit.offramp.orchestrator.OfframpDriver
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.p2p.getPriceConfig
import xyz.justzappit.offramp.p2p.getSmallOrderFixedFeePay
import xyz.justzappit.offramp.p2p.getSmallOrderThreshold
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

@Suppress("TooManyFunctions")
internal class UpiOfframpVM(
    private val navigationRouter: NavigationRouter,
    private val rpc: BaseRpcClient,
    private val network: P2pNetworkConfig,
    private val baseBalance: BaseBalanceRepository,
    private val checkpointStorage: OfframpCheckpointStorageProvider,
    private val orchestrator: OfframpDriver,
    private val currency: CurrencyCode,
    private val prescanned: PrescannedMerchantQr = PrescannedMerchantQr.EMPTY,
) : ViewModel() {
    // A fixed-amount merchant QR scanned on the home tab prefills the amount; an open QR leaves it blank.
    private val inrState =
        MutableStateFlow(
            prescanned.fiatAmount
                ?.let { NumberTextFieldInnerState.fromAmount(it) }
                ?: NumberTextFieldInnerState(),
        )
    private val pricing = MutableStateFlow(Pricing(rate = fallbackRate(currency)))
    private val inFlight = MutableStateFlow<OfframpCheckpoint?>(null)

    /**
     * Whether a merchant can actually be assigned for the amount currently typed.
     *
     * A corridor is not simply live or dead — assignability is per amount, and the two move
     * independently. At the time of writing PHP serves 10 USDC and refuses 11, so a corridor-level
     * flag cannot express it and a value measured last week cannot be trusted. The order would fail
     * safely either way, because the orchestrator selects a circle before it funds anything, but it
     * would fail after the user committed to an amount rather than while they were choosing one.
     */
    private val merchantProbe = MutableStateFlow<MerchantProbe>(MerchantProbe.Unknown)

    // Cleared on the next attempt and whenever the amount moves.
    private val quoteFailed = MutableStateFlow(false)

    private sealed interface MerchantProbe {
        /**
         * Nothing typed yet, a probe still in flight, or a probe that could not reach the chain —
         * never blocks the button. An unanswerable question must read the same as an unasked one,
         * or an RPC outage silently becomes a "corridor is dead" message.
         */
        object Unknown : MerchantProbe

        data class Checked(
            val usdc: Usdc6,
            val isAvailable: Boolean
        ) : MerchantProbe
    }

    /** The USDC/fiat pair a placed order carries, and the pair merchant eligibility is decided on. */
    private data class OrderAmounts(
        val usdc: Usdc6,
        val fiat: Usdc6,
    )

    // The two contract reads that drive the order math, held together so buildState sees a consistent
    // pair: the sell rate (INR→USDC) and the fixed fee the Diamond pulls on top of the placed amount.
    private data class Pricing(
        val rate: BigDecimal,
        val smallOrderThreshold: Usdc6 = Usdc6.ZERO,
        val smallOrderFixedFeePay: Usdc6 = Usdc6.ZERO,
    )

    // Surfaced separately from [state] so the confirmation sheet doesn't widen the main combine. Null = hidden.
    private val payConfirmationState = MutableStateFlow<ZappConfirmationState?>(null)
    val payConfirmation: StateFlow<ZappConfirmationState?> = payConfirmationState.asStateFlow()

    // Set on the main thread before launching the async re-quote so a double-tap can't run it twice.
    private var reQuoting = false

    // Driven by onStart/onCompletion on the state flow; the rate poller runs only while this is > 0,
    // so a backgrounded screen doesn't burn RPC quota.
    private val activeSubscribers = MutableStateFlow(0)

    // Folded into one flow because combine's typed arity stops at five.
    private val commitFeedback = combine(merchantProbe, quoteFailed) { probe, failed -> probe to failed }

    val state: StateFlow<UpiOfframpState> =
        combine(
            inrState,
            pricing,
            inFlight,
            baseBalance.balance,
            commitFeedback,
        ) { inr, currentPricing, checkpoint, balance, (probe, failed) ->
            buildState(
                inr = inr,
                pricing = currentPricing,
                inFlightCheckpoint = checkpoint,
                balance = balance.loadedOrNull,
                probe = probe,
                quoteFailed = failed,
            )
        }.onStart { activeSubscribers.update { it + 1 } }
            .onCompletion { activeSubscribers.update { (it - 1).coerceAtLeast(0) } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue =
                    buildState(
                        inr = inrState.value,
                        pricing = pricing.value,
                        inFlightCheckpoint = inFlight.value,
                        balance = baseBalance.balance.value.loadedOrNull,
                        probe = merchantProbe.value,
                        quoteFailed = quoteFailed.value,
                    ),
            )

    init {
        viewModelScope.launch {
            checkpointStorage
                .observe()
                .catch { e ->
                    if (e is StoreCorruptedException) {
                        Twig.warn(e) { "UpiOfframpVM: corrupted checkpoint blob, discarding" }
                        checkpointStorage.clear()
                        emit(null)
                    } else {
                        throw e
                    }
                }.collect { checkpoint -> inFlight.update { checkpoint } }
        }
        viewModelScope.launch {
            activeSubscribers
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { isSubscribed ->
                    if (!isSubscribed) return@collectLatest
                    pollPricing()
                }
        }
        viewModelScope.launch { pollMerchantAvailability() }
    }

    // The rate moves; the fee schedule is Diamond config, so it is read once per visit and again at
    // commit time, where a stale one would atomic-cancel the order.
    private suspend fun pollPricing() =
        coroutineScope {
            refreshFees()
            while (isActive) {
                refreshRate()
                delay(RATE_REFRESH_INTERVAL_MS)
            }
        }

    // Best-effort: a failed poll leaves the last good value in place, which is right here and wrong
    // at commit — see [readPricingForCommit].
    private suspend fun refreshRate() {
        bestEffort("UpiOfframpVM: getPriceConfig(${currency.code}) failed") {
            val newRate = readRate()
            Twig.info { "UpiOfframpVM: live sellPrice for ${currency.code} = $newRate" }
            pricing.update { it.copy(rate = newRate) }
        }
    }

    // Separate diamond reads; update each independently so one failing doesn't stale the other.
    private suspend fun refreshFees() {
        bestEffort("UpiOfframpVM: getSmallOrderThreshold(${currency.code}) failed") {
            val threshold = rpc.getSmallOrderThreshold(network.diamondAddress, currency)
            pricing.update { it.copy(smallOrderThreshold = threshold) }
        }
        bestEffort("UpiOfframpVM: getSmallOrderFixedFeePay(${currency.code}) failed") {
            val fee = rpc.getSmallOrderFixedFeePay(network.diamondAddress, currency)
            pricing.update { it.copy(smallOrderFixedFeePay = fee) }
        }
    }

    private suspend fun readRate(): BigDecimal = rpc.getPriceConfig(network.diamondAddress, currency).sellPriceAsRate()

    private fun buildState(
        inr: NumberTextFieldInnerState,
        pricing: Pricing,
        inFlightCheckpoint: OfframpCheckpoint?,
        balance: Usdc6?,
        probe: MerchantProbe,
        quoteFailed: Boolean,
    ): UpiOfframpState {
        // INR is the source of truth; USDC re-derives at the placed precision (see [alignUsdc]) and
        // nulls a sub-micro amount that floors to 0 USDC so Send stays disabled.
        val usdcAmount: BigDecimal? = inr.amount?.let { alignUsdc(it, pricing.rate) }
        val validationError = errorFor(inFlightCheckpoint, usdcAmount, probe, quoteFailed)
        val orderAmount: Usdc6? =
            if (inFlightCheckpoint == null && validationError == null && usdcAmount != null) {
                Usdc6.ofWhole(usdcAmount)
            } else {
                null
            }
        // The order pulls placed + fee from the Base balance, so the short/fund split is fee-inclusive.
        val payFee = orderAmount?.let { payFeeFor(it, pricing) } ?: Usdc6.ZERO
        val short = isShortOnMainnet(orderAmount, balance, payFee)
        return UpiOfframpState(
            inrInput =
                NumberTextFieldState(
                    innerState = inr,
                    isEnabled = prescanned.fiatAmount == null,
                    onValueChange = ::onInrChange,
                ),
            usdcEquivalent =
                usdcAmount?.let {
                    stringRes(
                        R.string.upi_offramp_usdc_equivalent,
                        Usdc6.ofWhole(it).toDisplayString(stripTrailingZeros = true),
                    )
                },
            currency = currency,
            fiatAmountText =
                inr.amount?.let {
                    stringRes(
                        R.string.upi_offramp_fiat_amount,
                        currency.symbol,
                        it.stripTrailingZeros().toPlainString(),
                    )
                },
            rateText =
                stringRes(
                    R.string.upi_offramp_rate_label,
                    currency.symbol + pricing.rate.stripTrailingZeros().toPlainString(),
                ),
            errorText = validationError,
            sendButton = sendButton(inFlightCheckpoint, short, validationError, usdcAmount),
            onHistoryClick = ::onHistoryClick,
            onAddFunds = ::onAddFunds,
            baseBalanceText =
                balance?.let {
                    stringRes(R.string.upi_offramp_base_balance_label, it.toDisplayString(stripTrailingZeros = true))
                },
            fundingPlanText =
                if (orderAmount != null && balance != null) {
                    fundingPlanText(orderAmount = orderAmount, balance = balance, fee = payFee)
                } else {
                    null
                },
            isTopUpNeeded = short,
            onDiscardInFlight = if (inFlightCheckpoint != null) ::onDiscardInFlight else null,
        )
    }

    private fun isShortOnMainnet(orderAmount: Usdc6?, balance: Usdc6?, fee: Usdc6): Boolean =
        orderAmount != null &&
            balance != null &&
            balance < requiredPlusFee(orderAmount, fee) &&
            network.chainId == P2pNetworks.MAINNET_CHAIN_ID

    private fun sendButton(
        inFlightCheckpoint: OfframpCheckpoint?,
        isShortOnMainnet: Boolean,
        validationError: StringResource?,
        usdcAmount: BigDecimal?,
    ): ButtonState {
        val text =
            when {
                inFlightCheckpoint != null -> stringRes(R.string.upi_offramp_resume_button)
                isShortOnMainnet -> stringRes(R.string.upi_offramp_pay_button_add_funds)
                else -> stringRes(R.string.upi_offramp_send_button)
            }
        val enabled =
            inFlightCheckpoint != null ||
                (validationError == null && usdcAmount != null)
        return ButtonState(text = text, isEnabled = enabled, onClick = ::onSendClick)
    }

    // Funded → pay straight from Base. Short on mainnet → hint the top-up step (the shortfall is
    // bridged first). Short on testnet → manual-fund hint (no NEAR route). "Funded" means covering
    // placed + fee, the full amount the order pulls from the balance.
    private fun fundingPlanText(orderAmount: Usdc6, balance: Usdc6, fee: Usdc6): StringResource {
        if (balance >= requiredPlusFee(orderAmount, fee)) return stringRes(R.string.upi_offramp_funding_from_base)
        return if (network.chainId == P2pNetworks.MAINNET_CHAIN_ID) {
            stringRes(
                R.string.upi_offramp_funding_topup_first,
                topUpShortfall(orderAmount, balance, fee).toDisplayString(stripTrailingZeros = true),
            )
        } else {
            stringRes(R.string.upi_offramp_funding_need_manual, orderAmount.toDisplayString(stripTrailingZeros = true))
        }
    }

    private fun onDiscardInFlight() {
        viewModelScope.launch { checkpointStorage.clear() }
    }

    private fun onHistoryClick() = navigationRouter.forward(P2pTransactionsArgs)

    private fun errorFor(
        inFlightCheckpoint: OfframpCheckpoint?,
        usdc: BigDecimal?,
        probe: MerchantProbe,
        quoteFailed: Boolean,
    ): StringResource? =
        if (inFlightCheckpoint != null) {
            stringRes(R.string.upi_offramp_error_in_flight)
        } else {
            // A failed re-quote outranks the probe beneath it, which was measured against a rate
            // the order was never allowed to use.
            validate(usdc)
                ?: stringRes(R.string.upi_offramp_error_quote_failed).takeIf { quoteFailed }
                ?: noMerchantError(usdc, probe)
        }

    private fun validate(usdc: BigDecimal?): StringResource? {
        // The 100-USDC cap only surfaces when the user actually exceeds it (no always-on hint).
        if (usdc != null && usdc > USDC_CAP) return stringRes(R.string.upi_offramp_limit_hint)
        return null
    }

    /**
     * Only speaks for the amount it was measured at. A probe for a different amount says nothing
     * about this one, and an in-flight probe must not disable Send — the user would see the button
     * die under their fingers on every keystroke.
     */
    private fun noMerchantError(usdc: BigDecimal?, probe: MerchantProbe): StringResource? {
        val checked = probe as? MerchantProbe.Checked ?: return null
        val speaksForThisAmount = usdc != null && checked.usdc == Usdc6.ofWhole(usdc)
        return if (speaksForThisAmount && !checked.isAvailable) {
            stringRes(R.string.upi_offramp_error_no_merchant)
        } else {
            null
        }
    }

    /**
     * Re-probes once the amount stops moving. Debounced because each probe is a subgraph read plus
     * an eligibility call per candidate circle, and the answer only matters once the user has
     * settled on a number.
     *
     * Keyed on the rate as well as the input: the placed USDC is derived from both, so a rate move
     * changes the amount being asked about even while the typed fiat sits still.
     */
    private suspend fun pollMerchantAvailability() {
        combine(inrState.map { it.amount }, pricing.map { it.rate }) { fiat, rate -> fiat to rate }
            .distinctUntilChanged()
            .collectLatest { (fiat, rate) ->
                val amounts = fiat?.let { orderAmounts(it, rate) }?.takeIf { it.usdc.whole <= USDC_CAP }
                if (amounts == null) {
                    merchantProbe.update { MerchantProbe.Unknown }
                    return@collectLatest
                }
                delay(MERCHANT_PROBE_DEBOUNCE_MS)
                merchantProbe.update { MerchantProbe.Unknown }
                merchantProbe.update { probe(amounts) }
            }
    }

    /**
     * Asks the chain about one exact pair and translates the answer into what the button should do.
     * A refusal is reported; an unreachable chain is not, because the router may still fill an order
     * this probe simply could not evaluate.
     */
    private suspend fun probe(amounts: OrderAmounts): MerchantProbe =
        when (val answer = orchestrator.merchantAvailability(amounts.usdc, amounts.fiat, currency)) {
            MerchantAvailability.Available -> {
                MerchantProbe.Checked(usdc = amounts.usdc, isAvailable = true)
            }

            MerchantAvailability.Unavailable -> {
                MerchantProbe.Checked(usdc = amounts.usdc, isAvailable = false)
            }

            is MerchantAvailability.Undetermined -> {
                Twig.warn(answer.cause) { "UpiOfframpVM: merchant probe failed for ${currency.code}" }
                MerchantProbe.Unknown
            }
        }

    private fun onInrChange(next: NumberTextFieldInnerState) {
        inrState.update { next }
        quoteFailed.update { false }
    }

    private fun onSendClick() {
        // If a checkpoint is in flight, jump back into the progress screen — it'll resume.
        inFlight.value?.let { existing ->
            navigationRouter.forward(resumeArgs(existing))
            return
        }
        if (reQuoting) return
        val rawInr = inrState.value.amount ?: return
        if (rawInr <= BigDecimal.ZERO) return
        reQuoting = true
        viewModelScope.launch {
            try {
                reQuoteAndRoute(rawInr)
            } finally {
                reQuoting = false
            }
        }
    }

    // Re-quote: the sell rate the contract stamps can drift, so refetch it the instant the user
    // commits, recompute the USDC, then either confirm a payment from the Base balance or route to a
    // top-up bridge when the balance is short. The pay flow never kicks off an inline bridge itself.
    @Suppress("ReturnCount")
    private suspend fun reQuoteAndRoute(rawInr: BigDecimal) {
        quoteFailed.update { false }
        val pricing =
            runCatching { readPricingForCommit() }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    Twig.warn(e) { "UpiOfframpVM: commit-time pricing read failed for ${currency.code}" }
                    quoteFailed.update { true }
                }.getOrNull() ?: return
        val amounts = orderAmounts(rawInr, pricing.rate)?.takeIf { it.usdc.whole <= USDC_CAP } ?: return

        // The re-quote can move the placed amount off whatever the input probe measured, and
        // assignability is per amount — so ask once more about the exact pair now being committed.
        // Only a definite refusal stops here; it lands in the same error slot the typed-amount probe
        // uses, so the screen explains itself instead of the button quietly doing nothing.
        val recheck = probe(amounts)
        merchantProbe.update { recheck }
        if (recheck is MerchantProbe.Checked && !recheck.isAvailable) return

        val snappedInr = snapFiat(rawInr)
        val requiredUsdc = amounts.usdc
        val fiatMicro = amounts.fiat.micros
        val fiatLimitMicro = fiatAmountLimit(requiredUsdc, pricing.rate)
        baseBalance.refresh()
        val balance = baseBalance.balance.value.loadedOrNull
        when {
            // The order pulls placed + fee from the Base balance, so confirm pay-from-Base only when it
            // covers both; a balance of exactly `placed` would make setSellOrderUpi atomic-cancel.
            balance != null && balance >= requiredPlusFee(requiredUsdc, payFeeFor(requiredUsdc, pricing)) -> {
                showPayConfirmation(snappedInr, requiredUsdc, pricing.rate, fiatMicro, fiatLimitMicro)
            }

            network.chainId == P2pNetworks.MAINNET_CHAIN_ID -> {
                val shortfall = topUpShortfall(requiredUsdc, balance, payFeeFor(requiredUsdc, pricing))
                navigationRouter.forward(
                    BridgeToBaseArgs(
                        prefillUsdcMicro = shortfall.micros.toString(),
                    ),
                )
            }

            // Testnet has no bridge; let the order flow surface PreFundedOfframpFunding's manual-fund guidance.
            else -> {
                navigationRouter.forward(progressArgs(requiredUsdc.micros, fiatMicro, fiatLimitMicro))
            }
        }
    }

    private fun showPayConfirmation(
        inr: BigDecimal,
        usdc: Usdc6,
        currentRate: BigDecimal,
        fiatMicro: BigInteger,
        fiatLimitMicro: BigInteger,
    ) {
        payConfirmationState.update {
            ZappConfirmationState(
                title = stringRes(R.string.upi_offramp_confirm_title),
                message =
                    stringRes(
                        R.string.upi_offramp_confirm_message,
                        currency.symbol + inr.toPlainString(),
                        usdc.toDisplayString(stripTrailingZeros = true),
                        currency.symbol + currentRate.stripTrailingZeros().toPlainString(),
                    ),
                primaryButton =
                    ButtonState(
                        text = stringRes(R.string.upi_offramp_confirm_pay),
                        onClick = {
                            payConfirmationState.update { null }
                            navigationRouter.forward(progressArgs(usdc.micros, fiatMicro, fiatLimitMicro))
                        },
                    ),
                secondaryButton =
                    ButtonState(
                        text = stringRes(R.string.upi_offramp_confirm_cancel),
                        onClick = { payConfirmationState.update { null } },
                    ),
                onBack = { payConfirmationState.update { null } },
            )
        }
    }

    private fun onAddFunds() = navigationRouter.forward(BridgeToBaseArgs())

    private fun progressArgs(
        usdcMicro: BigInteger,
        fiatMicro: BigInteger,
        fiatLimitMicro: BigInteger? = null,
    ) =
        UpiOfframpProgressArgs(
            recipientUpi = "",
            usdcAmountMicro = usdcMicro.toString(),
            fiatAmountMicro = fiatMicro.toString(),
            fiatAmountLimitMicro = fiatLimitMicro?.toString(),
            currency = currency,
            prescannedPayload = prescanned.rawPayload,
            prescannedPaymentAddress = prescanned.paymentAddress,
            prescannedFiatAmount = prescanned.fiatAmount?.toPlainString(),
        )

    private fun resumeArgs(existing: OfframpCheckpoint) =
        UpiOfframpProgressArgs(
            recipientUpi = existing.recipientUpi,
            usdcAmountMicro = existing.usdcAmountMicroDecimal,
            // Old checkpoints lack fiat — orchestrator resolves a fallback at resume time.
            fiatAmountMicro = existing.fiatAmountMicroDecimal ?: existing.usdcAmountMicroDecimal,
            fiatAmountLimitMicro = existing.fiatAmountLimitMicroDecimal,
            payeeName = existing.payeeName,
            currency = existing.currency,
        )

    private fun topUpShortfall(required: Usdc6, balance: Usdc6?, fee: Usdc6): Usdc6 {
        val have = balance?.micros ?: BigInteger.ZERO
        // Cover placed + fee, not bare placed: the Diamond pulls the fixed fee as a second transferFrom
        // at setUpi, so a balance of exactly `placed` underflows and atomic-cancels the order.
        val shortMicros = (requiredPlusFee(required, fee).micros - have).max(BigInteger.ONE)
        // Round the prefill up to the nearest 0.01 USDC so it comfortably covers the total.
        val step = USDC_TOPUP_ROUNDING_MICROS
        return Usdc6(((shortMicros + step - BigInteger.ONE) / step) * step)
    }

    // The fiat the order will carry, quantised to what the rail can actually charge.
    private fun snapFiat(raw: BigDecimal): BigDecimal = raw.setScale(currency.precision, RoundingMode.FLOOR)

    /**
     * The exact pair an order for [rawFiat] would carry at [rate]. Null when the input isn't a
     * placeable amount.
     *
     * Both the probe and the commit path derive their amounts here, so the question asked of the
     * chain cannot drift from the order that follows it — the two disagreeing is precisely what let
     * a corridor answer "yes" for an amount it would then refuse.
     */
    private fun orderAmounts(rawFiat: BigDecimal, rate: BigDecimal): OrderAmounts? {
        val aligned = alignUsdc(rawFiat, rate) ?: return null
        return OrderAmounts(usdc = Usdc6.ofWhole(aligned), fiat = Usdc6.ofWhole(snapFiat(rawFiat)))
    }

    // Snap INR to 2dp then floor-divide by the rate at 6dp — the exact precision the Diamond re-derives
    // from the URI's am= field, so the placed amount matches and setSellOrderUpi won't atomic-cancel.
    // Null for a non-positive or sub-micro amount (one that floors to 0 USDC).
    private fun alignUsdc(inr: BigDecimal, rate: BigDecimal): BigDecimal? =
        inr
            .takeIf { it > BigDecimal.ZERO }
            ?.setScale(currency.precision, RoundingMode.FLOOR)
            ?.divide(rate, USDC_INPUT_SCALE, RoundingMode.FLOOR)
            ?.takeIf { it > BigDecimal.ZERO }

    private fun requiredPlusFee(required: Usdc6, fee: Usdc6): Usdc6 = Usdc6(required.micros + fee.micros)

    private fun payFeeFor(amount: Usdc6, pricing: Pricing): Usdc6 =
        if (amount <= pricing.smallOrderThreshold) pricing.smallOrderFixedFeePay else Usdc6.ZERO

    private fun fiatAmountLimit(usdc: Usdc6, rate: BigDecimal): BigInteger {
        val rateMicros =
            rate
                .setScale(USDC_INPUT_SCALE, RoundingMode.FLOOR)
                .movePointRight(USDC_INPUT_SCALE)
                .toBigInteger()
        return usdc.micros.multiply(rateMicros).divide(MICROS_PER_UNIT)
    }

    /**
     * Read fresh and allowed to fail: unlike the poller this must not fall back to whatever [pricing]
     * holds, which on a screen whose first read has not landed is the seed constant and a zero fee.
     *
     * A stale-*low* rate still clears the Diamond's slippage check — `fiatAmountLimit` is a floor,
     * not a ceiling — so the order settles above the fiat the confirmation sheet showed. A zero fee
     * makes a balance of exactly the placed amount look sufficient, and setSellOrderUpi then
     * atomic-cancels on the fee's own transferFrom (see [topUpShortfall]).
     */
    private suspend fun readPricingForCommit(): Pricing {
        val fresh =
            Pricing(
                rate = readRate(),
                smallOrderThreshold = rpc.getSmallOrderThreshold(network.diamondAddress, currency),
                smallOrderFixedFeePay = rpc.getSmallOrderFixedFeePay(network.diamondAddress, currency),
            )
        pricing.value = fresh
        return fresh
    }

    companion object {
        // Pre-fetch placeholder rate per corridor, replaced by the live getPriceConfig read within
        // ~30s (and immediately on re-quote before any order is placed). Rough, just seeds the
        // estimate. Read off the mainnet Diamond's sellPrice on 2026-08-28; the inflationary
        // corridors (ARS, VEN, BOB, CUP) drift fastest, so re-measure when this table is touched.
        // ECU sits just under 1 because Ecuador is dollarised and the rate carries the spread.
        private fun fallbackRate(currency: CurrencyCode): BigDecimal =
            when (currency) {
                CurrencyCode.Inr -> BigDecimal("97")
                CurrencyCode.Brl -> BigDecimal("5.1")
                CurrencyCode.Idr -> BigDecimal("17400")
                CurrencyCode.Ars -> BigDecimal("1555")
                CurrencyCode.Ven -> BigDecimal("925")
                CurrencyCode.Ngn -> BigDecimal("1343")
                CurrencyCode.Cop -> BigDecimal("3063")
                CurrencyCode.Bob -> BigDecimal("11.6")
                CurrencyCode.Cup -> BigDecimal("928")
                CurrencyCode.Ecu -> BigDecimal("0.98")
                CurrencyCode.Pen -> BigDecimal("3.34")
                CurrencyCode.Php -> BigDecimal("60.5")
            }

        // Ours, not p2p.me's. Measured on mainnet 2026-08-28: the Diamond assigns merchants well
        // past this — INR to ~200 USDC, BRL and IDR to ~300 — and the SDK's corridor metadata
        // carries no amount bounds at all. The eligibility read answered identically for a fresh
        // address, the operator, and an account with 150 completed orders — three addresses, so read
        // it as no sign reputation moves it rather than proof it cannot. The only ceilings seen
        // enforced on chain are per-currency *monthly* volume and per-user rolling buy limits.
        //
        // Keep it as the deliberate self-custody safety rail it is, but do not mistake it for the
        // real limit: that is merchant liquidity, which is per corridor and per amount, and lower
        // than this in places — PHP stops around 10 USDC. The probe above is what actually knows.
        // Surfaced via R.string.upi_offramp_limit_hint and enforced here as a hard input cap.
        private val USDC_CAP: BigDecimal = BigDecimal("100")

        // Round a prefilled top-up amount up to the nearest 0.01 USDC (10_000 micros).
        private val USDC_TOPUP_ROUNDING_MICROS: BigInteger = BigInteger.valueOf(10_000)
        private val MICROS_PER_UNIT: BigInteger = BigInteger.valueOf(1_000_000)

        private const val USDC_INPUT_SCALE = 6

        private const val RATE_REFRESH_INTERVAL_MS = 30_000L

        private const val MERCHANT_PROBE_DEBOUNCE_MS = 600L
    }
}
