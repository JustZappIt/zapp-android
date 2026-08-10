package co.electriccoin.zcash.ui.screen.onramp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.provider.OnrampCheckpointStorageProvider
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.offramp.config.P2pNetworkConfig
import xyz.justzappit.offramp.onramp.OnrampCheckpoint
import xyz.justzappit.offramp.onramp.OnrampDriver
import xyz.justzappit.offramp.onramp.OnrampException
import xyz.justzappit.offramp.onramp.OnrampFailureCode
import xyz.justzappit.offramp.onramp.OnrampIntentAmount
import xyz.justzappit.offramp.onramp.OnrampLimits
import xyz.justzappit.offramp.onramp.OnrampPaymentInstruction
import xyz.justzappit.offramp.onramp.OnrampQuote
import xyz.justzappit.offramp.onramp.OnrampStatus
import xyz.justzappit.offramp.onramp.id
import xyz.justzappit.offramp.onramp.leavesOrderAlive
import xyz.justzappit.offramp.onramp.orderId
import xyz.justzappit.offramp.onramp.phase
import xyz.justzappit.offramp.orchestrator.OfframpStatus
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.p2p.getUsdcBalance
import xyz.justzappit.offramp.orchestrator.OfframpDriver as BaseRefundDriver

@Suppress("TooManyFunctions")
internal class OnrampVM(
    args: OnrampArgs,
    private val navigationRouter: NavigationRouter,
    private val rpc: BaseRpcClient,
    private val network: P2pNetworkConfig,
    private val driver: OnrampDriver,
    private val baseRefundDriver: BaseRefundDriver,
    private val checkpointStorage: OnrampCheckpointStorageProvider,
    private val copyToClipboard: CopyToClipboardUseCase,
) : ViewModel() {
    private val currency = CurrencyCode.fromCodeOrNull(args.currencyCode) ?: CurrencyCode.Inr
    private var limits: OnrampLimits = OnrampLimits.DISABLED
    private var recipient: Address? = null
    private var quote: OnrampQuote? = null
    private var quoteJob: Job? = null
    private var driverJob: Job? = null
    private var countdownJob: Job? = null
    private var expiryRecheckedFor: String? = null
    private var confirmPaidJob: Job? = null
    private var baseRefundJob: Job? = null

    private val mutableState =
        MutableStateFlow(
            OnrampState(
                currency = currency,
                paymentRail = currency.paymentRail(),
                amountInput = NumberTextFieldState(onValueChange = ::onAmountChange),
                onBack = ::onBack,
                onRetry = ::onRetry,
                onContinue = ::onContinue,
                onCopyAccountAddress = ::onCopyAccountAddress,
                onSendBaseBalanceToZec = ::onSendBaseBalanceToZec,
                onConfirmSendBaseBalanceToZec = ::onConfirmSendBaseBalanceToZec,
                onDismissSendBaseBalanceToZec = ::onDismissSendBaseBalanceToZec,
                onCopyPaymentAddress = ::onCopyPaymentAddress,
                onPaid = ::onPaid,
                onConfirmPaid = ::onConfirmPaid,
                onDismissPaidConfirm = ::onDismissPaidConfirm,
                onCancel = ::onCancel,
                onDone = ::onDone,
            ),
        )
    val state: StateFlow<OnrampState> = mutableState

    init {
        load()
    }

    private fun load() {
        mutableState.update { it.copy(mode = OnrampMode.LOADING, error = null) }
        viewModelScope.launch {
            val corridor = driver.limits(currency)
            limits = corridor
            val address = runCatching { driver.recipientAddress() }.getOrNull()
            recipient = address
            val balance = address?.let { runCatching { rpc.getUsdcBalance(network.usdcAddress, it) }.getOrNull() }
            val checkpoint = checkpointStorage.get()
            // The service serves one corridor at a time. Its bounds are only this corridor's if it
            // agrees, otherwise they would render under the wrong symbol and precision and the
            // quote would be rejected only after the user had typed an amount.
            val servesCorridor = corridor.enabled && corridor.currency == currency
            mutableState.update {
                it.copy(
                    mode =
                        when {
                            checkpoint != null -> OnrampMode.LOADING
                            servesCorridor && address != null -> OnrampMode.AMOUNT
                            else -> OnrampMode.UNAVAILABLE
                        },
                    accountAddress = address?.checksumHex,
                    addressExplorerUrl = address?.let { addr -> network.addressUrl(addr.checksumHex) },
                    baseBalance = balance?.toDisplayString(stripTrailingZeros = true),
                    isBaseRefundSupported = network.chainId == ChainId.BASE_MAINNET,
                    canSendBaseBalanceToZec =
                        network.chainId == ChainId.BASE_MAINNET && balance != null && balance > Usdc6.ZERO,
                    minFiat = corridor.minFiat.toFiatString(currency),
                    maxFiat = corridor.maxFiat.toFiatString(currency),
                    dailyLimit = corridor.perUserDailyFiat.toFiatString(currency),
                    error = if (servesCorridor && address == null) stringRes(R.string.onramp_error_loading) else null,
                )
            }
            checkpoint?.let(::resume)
        }
    }

    private fun onAmountChange(input: NumberTextFieldInnerState) {
        val fiat = input.amount?.let(Usdc6::ofWhole)
        val withinLimits = fiat != null && fiat >= limits.minFiat && fiat <= limits.maxFiat
        mutableState.update {
            it.copy(
                amountInput = NumberTextFieldState(innerState = input, onValueChange = ::onAmountChange),
                canContinue = limits.enabled && withinLimits,
                error = if (fiat == null || withinLimits) null else stringRes(R.string.onramp_error_limits),
            )
        }
    }

    private fun onContinue() {
        when (mutableState.value.mode) {
            OnrampMode.AMOUNT -> requestQuote()
            OnrampMode.CONFIRMATION -> placeOrder()
            else -> Unit
        }
    }

    /**
     * The service quantises the requested amount and returns its own [OnrampQuote.fiatAmount], so
     * everything shown from here on is the quote's numbers, not what the user typed.
     */
    private fun requestQuote() {
        if (mutableState.value.isSendingBaseBalanceToZec || quoteJob?.isActive == true) return
        val fiatWhole = mutableState.value.amountInput.innerState.amount ?: return
        mutableState.update { it.copy(canContinue = false, isRequestingQuote = true, error = null) }
        quoteJob =
            viewModelScope.launch {
                try {
                    applyQuote(driver.quote(Usdc6.ofWhole(fiatWhole), currency))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    mutableState.update {
                        it.copy(
                            canContinue = true,
                            isRequestingQuote = false,
                            error = e.toStringResource(),
                        )
                    }
                }
            }
    }

    private fun applyQuote(fresh: OnrampQuote) {
        quote = fresh
        mutableState.update {
            it.copy(
                mode = OnrampMode.CONFIRMATION,
                amountInput = it.amountInput.copy(isEnabled = false),
                quotedFiat = fresh.fiatAmount.toFiatString(currency),
                quotedNetUsdc = fresh.netUsdc.toDisplayString(stripTrailingZeros = true),
                quotedFee = fresh.feeUsdc.toDisplayString(stripTrailingZeros = true),
                quotedRate = fresh.buyPrice.toFiatString(currency),
                canContinue = true,
                isRequestingQuote = false,
                error = null,
            )
        }
        startQuoteCountdown(fresh)
    }

    private fun startQuoteCountdown(fresh: OnrampQuote) {
        countdownJob?.cancel()
        countdownJob =
            countdown(
                fresh.expiresAtMillis,
                onTick = { remaining -> mutableState.update { it.copy(quoteSecondsRemaining = remaining) } },
            ) {
                if (mutableState.value.mode == OnrampMode.CONFIRMATION) requestQuote()
            }
    }

    private fun placeOrder() {
        val active = quote ?: return
        if (mutableState.value.isSendingBaseBalanceToZec || baseRefundJob?.isActive == true) return
        // Cancelling the collector does not un-send a POST that has already left, so a second tap
        // inside one frame would place a second on-chain BUY.
        if (driverJob?.isActive == true) return
        countdownJob?.cancel()
        mutableState.update { it.copy(canContinue = false) }
        driverJob = viewModelScope.launch { driver.start(active).collect(::handleStatus) }
    }

    private fun onPaid() {
        val current = mutableState.value
        if (current.mode != OnrampMode.PAYMENT || current.isPaymentWindowClosed) return
        mutableState.update { it.copy(isPaidConfirmVisible = true) }
    }

    private fun onDismissPaidConfirm() {
        mutableState.update { it.copy(isPaidConfirmVisible = false) }
    }

    /** Fires once, from an explicit confirmation, and is never retried — see [OnrampDriver.confirmPaid]. */
    private fun onConfirmPaid() {
        // Re-checked rather than trusted from onPaid: the window can lapse while the confirmation
        // sheet is open, and asserting payment on a closed order releases a merchant's USDC
        // against an order the same screen is already calling closed.
        val current = mutableState.value
        mutableState.update { it.copy(isPaidConfirmVisible = false) }
        if (current.mode != OnrampMode.PAYMENT || !current.isPayable) return
        if (confirmPaidJob?.isActive == true) return
        driverJob?.cancel()
        driverJob =
            viewModelScope.launch {
                val stored = checkpointStorage.get() ?: return@launch
                driver.confirmPaid(stored).collect(::handleStatus)
            }
        confirmPaidJob = driverJob
    }

    private fun onCancel() {
        if (driverJob?.isActive == true && mutableState.value.progress is OnrampStatus.AwaitingMerchant) {
            return
        }
        driverJob?.cancel()
        driverJob =
            viewModelScope.launch {
                val stored = checkpointStorage.get() ?: return@launch
                driver.cancel(stored).collect(::handleStatus)
            }
    }

    private fun onCopyAccountAddress() {
        val address = mutableState.value.accountAddress ?: return
        copyToClipboard(value = address, isSensitive = true)
    }

    private fun onSendBaseBalanceToZec() {
        val current = mutableState.value
        if (
            current.mode != OnrampMode.AMOUNT ||
            current.isRequestingQuote ||
            !current.canSendBaseBalanceToZec ||
            quoteJob?.isActive == true ||
            driverJob?.isActive == true
        ) {
            return
        }
        mutableState.update { it.copy(isSendBaseBalanceConfirmVisible = true) }
    }

    private fun onDismissSendBaseBalanceToZec() {
        if (mutableState.value.isSendingBaseBalanceToZec) return
        mutableState.update { it.copy(isSendBaseBalanceConfirmVisible = false) }
    }

    /** Uses the same account-wide pullback as P2P transaction history (`orderId = null`). */
    private fun onConfirmSendBaseBalanceToZec() {
        val current = mutableState.value
        if (
            current.mode != OnrampMode.AMOUNT ||
            current.isRequestingQuote ||
            !current.canSendBaseBalanceToZec ||
            quoteJob?.isActive == true ||
            driverJob?.isActive == true ||
            baseRefundJob?.isActive == true
        ) {
            return
        }
        mutableState.update {
            it.copy(
                isSendBaseBalanceConfirmVisible = false,
                isSendingBaseBalanceToZec = true,
                sendBaseBalanceSuccess = null,
                sendBaseBalanceError = null,
            )
        }
        baseRefundJob =
            viewModelScope.launch {
                try {
                    baseRefundDriver.bridgeFundsBackToZec(orderId = null).collect { status ->
                        when (status) {
                            is OfframpStatus.FundsRecovered -> {
                                onBaseBalanceSentToZec(status)
                            }

                            is OfframpStatus.Failed -> {
                                logBaseRefundFailure(status.message, status.cause)
                                mutableState.update {
                                    it.copy(
                                        sendBaseBalanceError = stringRes(R.string.onramp_send_to_zec_failed),
                                    )
                                }
                            }

                            else -> {
                                Unit
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logBaseRefundFailure("driver failed before reporting a status", e)
                    mutableState.update {
                        it.copy(sendBaseBalanceError = stringRes(R.string.onramp_send_to_zec_failed))
                    }
                } finally {
                    mutableState.update { it.copy(isSendingBaseBalanceToZec = false) }
                }
            }
    }

    private suspend fun onBaseBalanceSentToZec(status: OfframpStatus.FundsRecovered) {
        mutableState.update {
            it.copy(
                baseBalance = Usdc6.ZERO.toDisplayString(stripTrailingZeros = true),
                canSendBaseBalanceToZec = false,
                isSendingBaseBalanceToZec = false,
                sendBaseBalanceSuccess =
                    stringRes(
                        R.string.onramp_send_to_zec_succeeded,
                        status.amount.toDisplayString(stripTrailingZeros = true),
                    ),
                sendBaseBalanceError = null,
            )
        }
        refreshBaseBalanceAfterRefund()
    }

    private suspend fun refreshBaseBalanceAfterRefund() {
        val address = recipient ?: return
        val balance =
            runCatching { rpc.getUsdcBalance(network.usdcAddress, address) }
                .onFailure { Twig.warn(it) { "OnrampVM: Base balance refresh after refund failed" } }
                .getOrNull()
                ?: return
        mutableState.update {
            it.copy(
                baseBalance = balance.toDisplayString(stripTrailingZeros = true),
                canSendBaseBalanceToZec = network.chainId == ChainId.BASE_MAINNET && balance > Usdc6.ZERO,
            )
        }
    }

    private fun logBaseRefundFailure(message: String, cause: Throwable?) {
        if (cause == null) {
            Twig.warn { "OnrampVM: Send to ZEC failed: $message" }
        } else {
            Twig.warn(cause) { "OnrampVM: Send to ZEC failed: $message" }
        }
    }

    private fun onCopyPaymentAddress() {
        val address = mutableState.value.paymentAddress ?: return
        copyToClipboard(value = address, isSensitive = true)
    }

    private fun onBack() {
        if (mutableState.value.isSendingBaseBalanceToZec) return
        navigationRouter.back()
    }

    private fun onRetry() {
        if (mutableState.value.isSendingBaseBalanceToZec) return
        quote = null
        expiryRecheckedFor = null
        quoteJob?.cancel()
        countdownJob?.cancel()
        driverJob?.cancel()
        baseRefundJob?.cancel()
        mutableState.update {
            it.copy(
                amountInput = NumberTextFieldState(onValueChange = ::onAmountChange),
                quotedFiat = null,
                quotedNetUsdc = null,
                quotedFee = null,
                quotedRate = null,
                quoteSecondsRemaining = null,
                orderId = null,
                paymentInstruction = null,
                paymentAmount = null,
                paymentSecondsRemaining = null,
                progress = null,
                canContinue = false,
                isRequestingQuote = false,
                isSendBaseBalanceConfirmVisible = false,
                sendBaseBalanceSuccess = null,
                sendBaseBalanceError = null,
            )
        }
        viewModelScope.launch {
            checkpointStorage.clear()
            load()
        }
    }

    private fun onDone() {
        viewModelScope.launch { checkpointStorage.clear() }
        navigationRouter.backToRoot()
    }

    private fun resume(checkpoint: OnrampCheckpoint) {
        driverJob?.cancel()
        driverJob = viewModelScope.launch { driver.resume(checkpoint).collect(::handleStatus) }
    }

    private suspend fun handleStatus(status: OnrampStatus) {
        persist(status)
        val mismatched =
            status is OnrampStatus.AwaitingPayment &&
                OnrampIntentAmount.disagreesWith(currency, status.instruction, status.fiatAmount)
        mutableState.update { previous ->
            val current = previous.copy(isSendBaseBalanceConfirmVisible = false)
            when (status) {
                is OnrampStatus.AwaitingPayment -> {
                    current.copy(
                        mode = OnrampMode.PAYMENT,
                        paymentInstruction = status.instruction,
                        paymentAmount = status.payableAmount(),
                        isPaymentAmountUntrusted = mismatched,
                        progress = status,
                        orderId = status.orderId ?: current.orderId,
                        // A request we will not let the user pay must not leave a sheet open
                        // offering to confirm they already did.
                        isPaidConfirmVisible = current.isPaidConfirmVisible && !mismatched,
                        error = null,
                    )
                }

                is OnrampStatus.Completed -> {
                    current.copy(
                        mode = OnrampMode.COMPLETION,
                        receivedUsdc = status.netUsdc.toDisplayString(stripTrailingZeros = true),
                        fiatPaid = status.fiatAmount.toFiatString(currency),
                        transactionExplorerUrl = status.paidTx?.let(network::txUrl),
                        progress = status,
                        orderId = status.orderId ?: current.orderId,
                        paymentInstruction = null,
                        paymentSecondsRemaining = null,
                        isPaidConfirmVisible = false,
                        error = null,
                    )
                }

                is OnrampStatus.Failed -> {
                    current.copy(
                        mode = OnrampMode.PROGRESS,
                        progress = status,
                        orderId = status.orderId ?: current.orderId,
                        paymentInstruction = null,
                        paymentSecondsRemaining = null,
                        isPaidConfirmVisible = false,
                        error = status.code.toStringResource(),
                    )
                }

                else -> {
                    current.copy(
                        mode = OnrampMode.PROGRESS,
                        progress = status,
                        orderId = status.orderId ?: current.orderId,
                        paymentInstruction = null,
                        paymentSecondsRemaining = null,
                        error = null,
                    )
                }
            }
        }
        if (status is OnrampStatus.AwaitingPayment) logIntent(status)
        startPaymentCountdown(status)
    }

    /**
     * What the user must actually transfer.
     *
     * Deliberately the order's own [fiatAmount] rather than the instruction's `amount` field. The
     * two are separate values on the wire and only the request's `am=` is checked against the order
     * by [OnrampIntentAmount]; rendering an unchecked third number invites a user to type a figure
     * nothing validated, while the QR beside it charges another.
     */
    private fun OnrampStatus.AwaitingPayment.payableAmount(): String = fiatAmount.toFiatString(currency)

    private suspend fun logIntent(status: OnrampStatus.AwaitingPayment) {
        if (!BuildConfig.DEBUG) return
        val declared = OnrampIntentAmount.declaredAmount(currency, status.instruction)
        Twig.info {
            "Onramp payment: order=${status.orderId} fiatAmount=${status.fiatAmount.micros}" +
                " declared=${declared?.micros} expiresAt=${status.expiresAtMillis}" +
                " now=${System.currentTimeMillis()} instruction=${status.instruction.debugUri()}"
        }
    }

    private fun OnrampPaymentInstruction.debugUri(): String =
        when (this) {
            is OnrampPaymentInstruction.Upi -> intentUrl
            is OnrampPaymentInstruction.Qr -> payload
            is OnrampPaymentInstruction.Plain -> address
            is OnrampPaymentInstruction.Fields -> fields.joinToString { "${it.label}=${it.value}" }
        }

    private fun startPaymentCountdown(status: OnrampStatus) {
        if (status !is OnrampStatus.AwaitingPayment) return
        countdownJob?.cancel()
        countdownJob =
            countdown(
                status.expiresAtMillis,
                onTick = { remaining -> mutableState.update { it.copy(paymentSecondsRemaining = remaining) } },
            ) {
                // Polling stops at AWAITING_PAYMENT, so nothing else would notice the window
                // closing, and once per order because the service can leave an order there past
                // its own expiresAt.
                if (expiryRecheckedFor != status.id) {
                    expiryRecheckedFor = status.id
                    checkpointStorage.get()?.let(::resume)
                }
            }
    }

    /**
     * Ticks a service deadline down and runs [onExpired] once it lapses. A deadline the service did
     * not give must not read as "already expired": that fires [onExpired] with no delay before it,
     * which for the quote means re-quoting in a tight loop.
     */
    private fun countdown(
        expiresAtMillis: Long?,
        onTick: (Long) -> Unit,
        onExpired: suspend () -> Unit,
    ): Job? {
        val expiresAt = expiresAtMillis?.let(::asEpochMillis) ?: return null
        return viewModelScope.launch {
            while (true) {
                val remaining = (expiresAt - System.currentTimeMillis()) / MILLIS_PER_SECOND
                onTick(remaining.coerceAtLeast(0))
                if (remaining <= 0) break
                delay(MILLIS_PER_SECOND)
            }
            onExpired()
        }
    }

    private suspend fun persist(status: OnrampStatus) {
        val id = status.id ?: return
        if (status.isSettled) {
            checkpointStorage.clear()
            return
        }
        checkpointStorage.store(
            OnrampCheckpoint(
                id = id,
                phase = status.phase,
                orderId = status.orderId ?: checkpointStorage.get()?.orderId,
            ),
        )
    }

    private val OnrampStatus.isSettled: Boolean
        get() =
            this is OnrampStatus.Completed ||
                this is OnrampStatus.Cancelled ||
                (this is OnrampStatus.Failed && !leavesOrderAlive)

    private fun Throwable.toStringResource(): StringResource =
        (this as? OnrampException)?.code?.toStringResource() ?: stringRes(R.string.onramp_error_starting)

    @Suppress("CyclomaticComplexMethod")
    private fun OnrampFailureCode.toStringResource(): StringResource =
        when (this) {
            OnrampFailureCode.BAD_REQUEST -> stringRes(R.string.onramp_error_limits)

            OnrampFailureCode.UNAUTHENTICATED,
            OnrampFailureCode.NONCE_INVALID,
            -> stringRes(R.string.onramp_error_unauthenticated)

            OnrampFailureCode.RECIPIENT_NOT_ALLOWED -> stringRes(R.string.onramp_error_recipient_not_allowed)

            OnrampFailureCode.ROUTE_DISABLED -> stringRes(R.string.onramp_error_corridor_disabled)

            OnrampFailureCode.ORDER_NOT_FOUND -> stringRes(R.string.onramp_error_order_not_found)

            OnrampFailureCode.WRONG_PHASE -> stringRes(R.string.onramp_error_wrong_phase)

            OnrampFailureCode.QUOTE_EXPIRED -> stringRes(R.string.onramp_error_quote_expired)

            OnrampFailureCode.CAP_EXCEEDED -> stringRes(R.string.onramp_error_cap_exceeded)

            OnrampFailureCode.SCREENING_REJECTED -> stringRes(R.string.onramp_error_screening_rejected)

            OnrampFailureCode.UPSTREAM_FAILED,
            OnrampFailureCode.OPERATOR_UNAVAILABLE,
            OnrampFailureCode.NETWORK_UNAVAILABLE,
            -> stringRes(R.string.onramp_error_backend_unavailable)

            OnrampFailureCode.NO_MERCHANT -> stringRes(R.string.onramp_error_no_merchant)

            OnrampFailureCode.ORDER_EXPIRED -> stringRes(R.string.onramp_error_order_expired)

            OnrampFailureCode.UNKNOWN -> stringRes(R.string.onramp_error_progress)
        }

    // Rail names are brand nouns and identical across locales, so they are literals. NGN's is the
    // exception: "Bank transfer" is prose, and prose goes through strings.xml like everything else.
    private fun CurrencyCode.paymentRail(): StringResource =
        when (this) {
            CurrencyCode.Inr -> stringRes("UPI")
            CurrencyCode.Brl -> stringRes("PIX")
            CurrencyCode.Idr -> stringRes("QRIS")
            CurrencyCode.Ars -> stringRes("Mercado Pago")
            CurrencyCode.Ven -> stringRes("Pago Móvil")
            CurrencyCode.Ngn -> stringRes(R.string.onramp_payment_rail_bank_transfer)
            CurrencyCode.Cop -> stringRes("Nequi")
        }

    /**
     * The service documents these as milliseconds. A seconds value read as millis lands in 1970 and
     * the window reads as permanently closed. Null means no deadline, not one that has passed.
     */
    private fun asEpochMillis(value: Long): Long? =
        when {
            value <= 0 -> null
            value < SECONDS_THRESHOLD -> value * MILLIS_PER_SECOND
            else -> value
        }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L

        // 1e11 ms is 1973; 1e11 s is the year 5138. No real timestamp is ambiguous across it.
        const val SECONDS_THRESHOLD = 100_000_000_000L
    }
}
