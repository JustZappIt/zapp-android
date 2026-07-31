package co.electriccoin.zcash.ui.screen.swap.upi.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.provider.OfframpCheckpointStorageProvider
import co.electriccoin.zcash.ui.common.provider.StoreCorruptedException
import co.electriccoin.zcash.ui.common.usecase.NavigateToScanUpiUseCase
import co.electriccoin.zcash.ui.common.usecase.ScanUpiResult
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.ellipsizeMiddle
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.offramp.config.P2pNetworkConfig
import xyz.justzappit.offramp.orchestrator.KnownRevertReason
import xyz.justzappit.offramp.orchestrator.OfframpDriver
import xyz.justzappit.offramp.orchestrator.OfframpPaymentDetails
import xyz.justzappit.offramp.orchestrator.OfframpPaymentDetailsProvider
import xyz.justzappit.offramp.orchestrator.OfframpRequest
import xyz.justzappit.offramp.orchestrator.OfframpStatus
import xyz.justzappit.offramp.orchestrator.orderId
import xyz.justzappit.offramp.orchestrator.step
import xyz.justzappit.offramp.p2p.OrderFeeDetails
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.p2p.getAdditionalOrderDetails
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Suppress("TooManyFunctions")
internal class UpiOfframpProgressVM(
    private val args: UpiOfframpProgressArgs,
    private val orchestrator: OfframpDriver,
    private val network: P2pNetworkConfig,
    private val navigationRouter: NavigationRouter,
    private val checkpointStorage: OfframpCheckpointStorageProvider,
    private val rpc: BaseRpcClient,
    private val navigateToScanUpi: NavigateToScanUpiUseCase,
) : ViewModel() {
    private val request: OfframpRequest =
        run {
            val fiat = Usdc6(BigInteger(args.fiatAmountMicro))
            OfframpRequest(
                recipientUpi = args.recipientUpi,
                usdcAmount = Usdc6(BigInteger(args.usdcAmountMicro)),
                fiatAmount = fiat,
                payeeName = args.payeeName,
                currency = args.currency,
                fiatAmountLimit = args.fiatAmountLimitMicro?.let { Usdc6(BigInteger(it)) } ?: fiat,
            )
        }

    private val persister = OfframpCheckpointPersister(storage = checkpointStorage, request = request)

    private val feeDetails = MutableStateFlow<OrderFeeDetails?>(null)

    // Sticky funding observations: hide the funding row entirely when Base was already funded, but
    // keep "Bridging funds" visible after a real bridge starts so users can see why setup is slower.
    private val fundingObservations = MutableStateFlow(FundingObservations())

    // Full history so a late subscriber (rotation, dialog dismiss) sees the prefix, not just the
    // latest step. The orchestrator's cold Flow is collected exactly once in `init`; every
    // downstream consumer reads from this list.
    private val statusList = MutableStateFlow<List<OfframpStatus>>(emptyList())
    private val pendingScan = MutableStateFlow<CompletableDeferred<ScanUpiResult>?>(null)
    private val scannedRecipient = MutableStateFlow<String?>(null)
    private var scanLaunchInFlight = false
    private val paymentDetailsProvider =
        OfframpPaymentDetailsProvider { orderId, accepted, request ->
            requestPaymentDetails(orderId, accepted, request)
        }

    init {
        // Drive the orchestrator. Single collector, full history captured, side effects co-located.
        viewModelScope.launch {
            val existing =
                try {
                    checkpointStorage.get()
                } catch (e: StoreCorruptedException) {
                    Twig.warn(e) { "UpiOfframpProgress: corrupted checkpoint blob, discarding" }
                    checkpointStorage.clear()
                    null
                }
            // Resume whenever there's an order already placed OR a funding bridge in flight: the
            // bridge's persisted 1-Click deposit address must be re-polled, never re-quoted, or a
            // crash mid-bridge would open a second bridge and double-send the user's ZEC. Only a
            // checkpoint with neither is empty noise worth discarding.
            val upstream =
                if (existing != null && (existing.orderIdBig != null || existing.bridgeDepositAddress != null)) {
                    Twig.info {
                        "UpiOfframpProgress resuming from ${existing.currentStep} " +
                            "(orderId=${existing.orderId}, bridge=${existing.bridgeDepositAddress != null})"
                    }
                    persister.seedFrom(existing)
                    orchestrator.resume(existing, paymentDetailsProvider)
                } else {
                    if (existing != null) {
                        Twig.warn { "UpiOfframpProgress: discarding empty checkpoint at ${existing.currentStep}" }
                        checkpointStorage.clear()
                    }
                    orchestrator.run(request, paymentDetailsProvider)
                }
            upstream.collect { status ->
                Twig.info { "UpiOfframpProgress status=$status" }
                persister.onStatus(status)
                if (status is OfframpStatus.FundedFromBase) {
                    fundingObservations.update { it.copy(fundedFromBase = true) }
                }
                if (status is OfframpStatus.BridgingFunds) {
                    fundingObservations.update { it.copy(bridging = true) }
                }
                statusList.update { it + status }
            }
        }

        // Fee details: refetch whenever orderId or status-class changes. distinctUntilChanged
        // throttles the WaitingForCompletion poll loop (which emits every 3s) down to one fetch
        // per genuine state transition.
        viewModelScope.launch {
            statusList
                .mapNotNull { list ->
                    val last = list.lastOrNull() ?: return@mapNotNull null
                    last.orderId?.let { it to last::class }
                }.distinctUntilChanged()
                .collect { (orderId, _) ->
                    runCatching { rpc.getAdditionalOrderDetails(network.diamondAddress, orderId) }
                        .onSuccess { details -> feeDetails.update { details } }
                        .onFailure { Twig.warn(it) { "UpiOfframpProgress: getAdditionalOrderDetails($orderId) failed" } }
                }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun requestPaymentDetails(
        orderId: BigInteger,
        accepted: xyz.justzappit.offramp.p2p.OrderSnapshot,
        request: OfframpRequest,
    ): OfframpPaymentDetails {
        // Home-scan flow: the QR was already scanned and validated before the order was placed, so
        // skip the mid-order corridor scanner and use the carried payload directly.
        val prescannedPayload = args.prescannedPayload
        val prescannedAddress = args.prescannedPaymentAddress
        if (prescannedPayload != null && prescannedAddress != null) {
            scannedRecipient.update { displayRecipient(prescannedAddress) }
            return OfframpPaymentDetails(
                rawPayload = prescannedPayload,
                paymentAddress = prescannedAddress,
                fiatAmount = args.prescannedFiatAmount?.let { runCatching { BigDecimal(it) }.getOrNull() },
            )
        }
        val deferred = CompletableDeferred<ScanUpiResult>()
        pendingScan.update { deferred }
        launchScanForPendingPaymentDetails()
        val scan = deferred.await()
        pendingScan.update { current -> if (current == deferred) null else current }
        scannedRecipient.update { displayRecipient(scan.paymentAddress) }
        return OfframpPaymentDetails(
            rawPayload = scan.rawPayload,
            paymentAddress = scan.paymentAddress,
            fiatAmount = scan.fiatAmount,
        )
    }

    private fun launchScanForPendingPaymentDetails() {
        val deferred = pendingScan.value
        if (deferred == null || deferred.isCompleted || scanLaunchInFlight) return
        scanLaunchInFlight = true
        viewModelScope.launch {
            try {
                val result = navigateToScanUpi(args.currency)
                if (result != null && !deferred.isCompleted) {
                    deferred.complete(result)
                }
            } finally {
                scanLaunchInFlight = false
            }
        }
    }

    private data class ProgressInputs(
        val status: OfframpStatus,
        val fees: OrderFeeDetails?,
        val fundingObservations: FundingObservations,
        val pendingScan: CompletableDeferred<ScanUpiResult>?,
    )

    private data class FundingObservations(
        val fundedFromBase: Boolean = false,
        val bridging: Boolean = false,
    )

    val state: StateFlow<UpiOfframpProgressState> =
        combine(
            statusList,
            feeDetails,
            fundingObservations,
            pendingScan,
        ) { list, fees, fundingObservations, pendingScan ->
            ProgressInputs(
                status = list.lastOrNull() ?: OfframpStatus.Idle,
                fees = fees,
                fundingObservations = fundingObservations,
                pendingScan = pendingScan,
            )
        }.combine(scannedRecipient) { inputs, recipient ->
            buildState(
                status = inputs.status,
                fees = inputs.fees,
                fundingObservations = inputs.fundingObservations,
                pendingScan = inputs.pendingScan,
                scannedRecipient = recipient,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = buildState(OfframpStatus.Idle, null, FundingObservations(), null, null),
        )

    private fun buildState(
        status: OfframpStatus,
        fees: OrderFeeDetails?,
        fundingObservations: FundingObservations,
        pendingScan: CompletableDeferred<ScanUpiResult>?,
        scannedRecipient: String?,
    ): UpiOfframpProgressState {
        val orderId = status.orderId
        val summary = buildSummary(status, orderId, scannedRecipient)

        val title =
            when (status) {
                is OfframpStatus.Completed -> stringRes(R.string.upi_offramp_progress_title_completed)
                is OfframpStatus.Cancelled -> stringRes(R.string.upi_offramp_progress_title_cancelled)
                is OfframpStatus.Failed -> stringRes(R.string.upi_offramp_progress_title_failed)
                is OfframpStatus.WaitingForPaymentDetails -> stringRes(R.string.upi_offramp_progress_title_scan_qr)
                else -> stringRes(R.string.upi_offramp_progress_title_in_progress)
            }

        val subtitle: StringResource? =
            when (status) {
                is OfframpStatus.Completed -> {
                    stringRes(R.string.upi_offramp_progress_subtitle_completed)
                }

                is OfframpStatus.Cancelled -> {
                    stringRes(R.string.upi_offramp_progress_subtitle_cancelled)
                }

                is OfframpStatus.Failed -> {
                    null
                }

                is OfframpStatus.WaitingForPaymentDetails -> {
                    stringRes(R.string.upi_offramp_progress_subtitle_scan_qr)
                }

                else -> {
                    scannedRecipient
                        ?.let { stringRes(R.string.upi_offramp_progress_subtitle_recipient, it) }
                        ?: args.recipientUpi
                            .takeIf { it.isNotBlank() }
                            ?.let { stringRes(R.string.upi_offramp_progress_subtitle_recipient, it) }
                }
            }

        val steps =
            buildProgressSteps(
                status = status,
                currency = args.currency,
                fundedFromBaseObserved = fundingObservations.fundedFromBase,
                bridgingObserved = fundingObservations.bridging,
            )
        val failure = (status as? OfframpStatus.Failed)?.let(::buildFailureCard)
        val cancelled = (status as? OfframpStatus.Cancelled)?.let(::buildCancelledCard)
        val feeBreakdown = buildFeeBreakdown(fees)

        // Refund-back-to-ZEC is reachable only from Settings → P2P transactions, never inline here.
        // Keeps the in-flow primary action a single "done / close" once the order is terminal.
        return UpiOfframpProgressState(
            title = title,
            subtitle = subtitle,
            summary = summary,
            feeBreakdown = feeBreakdown,
            steps = steps,
            failure = failure,
            cancelled = cancelled,
            primaryButton = primaryButtonFor(status, pendingScan),
            isCompleted = status is OfframpStatus.Completed,
            onBack = { navigationRouter.back() },
        )
    }

    private fun primaryButtonFor(
        status: OfframpStatus,
        pendingScan: CompletableDeferred<ScanUpiResult>?,
    ): ButtonState? =
        when (status) {
            is OfframpStatus.WaitingForPaymentDetails -> {
                ButtonState(
                    text = stringRes(R.string.upi_offramp_scan_merchant_qr_button),
                    isEnabled = pendingScan != null,
                    onClick = ::launchScanForPendingPaymentDetails,
                )
            }

            is OfframpStatus.Completed -> {
                ButtonState(
                    text = stringRes(R.string.upi_offramp_progress_done_button),
                    onClick = { navigationRouter.back() },
                )
            }

            is OfframpStatus.Cancelled, is OfframpStatus.Failed -> {
                ButtonState(
                    text = stringRes(R.string.upi_offramp_progress_close_button),
                    onClick = { navigationRouter.back() },
                )
            }

            else -> {
                null
            }
        }

    private fun buildSummary(
        status: OfframpStatus,
        orderId: BigInteger?,
        scannedRecipient: String?,
    ): UpiOfframpOrderSummary {
        val completionDuration =
            (status as? OfframpStatus.Completed)?.let {
                completionDurationString(it.placedAtEpochSeconds, it.completedAtEpochSeconds)
            }
        val terminalTimestamp =
            when (status) {
                is OfframpStatus.Completed -> status.completedAtEpochSeconds?.let(::formatTerminalTimestamp)
                is OfframpStatus.Cancelled -> status.cancelledAtEpochSeconds?.let(::formatTerminalTimestamp)
                else -> null
            }
        return UpiOfframpOrderSummary(
            amountUsdcDisplay =
                stringRes(
                    R.string.upi_offramp_progress_amount_usdc,
                    runCatching { Usdc6(BigInteger(args.usdcAmountMicro)).toDisplayString() }.getOrDefault("0"),
                ),
            recipient =
                scannedRecipient
                    ?.let(::stringRes)
                    ?: args.recipientUpi
                        .takeIf { it.isNotBlank() }
                        ?.let(::stringRes)
                    ?: stringRes(R.string.upi_offramp_summary_recipient_pending),
            orderId = orderId?.toString(),
            completionDuration = completionDuration,
            terminalTimestamp = terminalTimestamp,
        )
    }

    private fun buildFeeBreakdown(fees: OrderFeeDetails?): UpiOfframpFeeBreakdown? {
        if (fees == null) return null
        // Pre-acceptance the contract returns all-zeros; rendering that gives a misleading
        // "fee = 0.00 USDC" line. Only surface the card once at least one value is meaningful.
        if (fees.fixedFeePaid == Usdc6.ZERO && fees.actualUsdcAmount == Usdc6.ZERO) return null
        val youSend =
            fees.actualUsdcAmount
                .takeIf { it > Usdc6.ZERO }
                ?.let { stringRes(R.string.upi_offramp_fee_breakdown_amount_usdc, displayUsdc(it)) }
        val fee =
            fees.fixedFeePaid
                .takeIf { it > Usdc6.ZERO }
                ?.let { stringRes(R.string.upi_offramp_fee_breakdown_amount_usdc, displayUsdc(it)) }
        val youReceive =
            fees.actualFiatAmount
                .takeIf { it > Usdc6.ZERO }
                ?.let { stringRes(args.currency.symbol + displayFiat(it)) }
        return UpiOfframpFeeBreakdown(youSend = youSend, fee = fee, youReceive = youReceive)
    }

    private fun buildCancelledCard(cancelled: OfframpStatus.Cancelled): UpiOfframpCancelledCard {
        val reassurance =
            cancelled.refundedUsdcAmount
                ?.let { stringRes(R.string.upi_offramp_cancelled_balance_unchanged, displayUsdc(it)) }
                ?: stringRes(R.string.upi_offramp_cancelled_no_payment)
        val cancelledAt =
            cancelled.cancelledAtEpochSeconds?.let {
                stringRes(R.string.upi_offramp_cancelled_at, formatTerminalTimestampValue(it))
            }
        return UpiOfframpCancelledCard(
            reassurance = reassurance,
            cancelledAt = cancelledAt,
        )
    }

    private fun buildFailureCard(failed: OfframpStatus.Failed): UpiOfframpFailureCard {
        val txHashHex = failed.txHash?.hex
        return UpiOfframpFailureCard(
            stepLabel = stepLabel(failed.step, args.currency),
            decodedReason = decodedReason(failed),
            txHash = txHashHex,
            txExplorerUrl = txHashHex?.let { network.txUrl(it) },
        )
    }

    private fun decodedReason(failed: OfframpStatus.Failed): StringResource? {
        failed.knownRevertReason?.let { return stringRes(curatedRevertStringRes(it)) }
        (failed.sdkErrorMessage ?: failed.sdkErrorName)?.let {
            return stringRes(R.string.upi_offramp_revert_sdk_long_tail, it)
        }
        return failed.solidityErrorString?.let(::stringRes)
    }

    private fun curatedRevertStringRes(reason: KnownRevertReason): Int =
        when (reason) {
            KnownRevertReason.BuyOrderAmountExceedsLimit -> R.string.upi_offramp_revert_buy_order_amount_exceeds_limit
            KnownRevertReason.InsufficientReputation -> R.string.upi_offramp_revert_insufficient_reputation
            KnownRevertReason.OrderAmountExceedsLimit -> R.string.upi_offramp_revert_order_amount_exceeds_limit
            KnownRevertReason.SellAmountExceedsFiatLimit -> R.string.upi_offramp_revert_sell_amount_exceeds_fiat_limit
            KnownRevertReason.CurrencyNotSupported -> R.string.upi_offramp_revert_currency_not_supported
            KnownRevertReason.UserIsBlacklisted -> R.string.upi_offramp_revert_user_is_blacklisted
            KnownRevertReason.ExchangeNotOperational -> R.string.upi_offramp_revert_exchange_not_operational
            KnownRevertReason.NotEnoughEligibleMerchants -> R.string.upi_offramp_revert_not_enough_eligible_merchants
            KnownRevertReason.OrderExpired -> R.string.upi_offramp_revert_order_expired
            KnownRevertReason.UpiAlreadySent -> R.string.upi_offramp_revert_upi_already_sent
            KnownRevertReason.InvalidOrderUpi -> R.string.upi_offramp_revert_invalid_order_upi
            KnownRevertReason.OrderNotAccepted -> R.string.upi_offramp_revert_order_not_accepted
            KnownRevertReason.UsdcTransferFailed -> R.string.upi_offramp_revert_usdc_transfer_failed
            KnownRevertReason.NotAuthorized -> R.string.upi_offramp_revert_not_authorized
        }

    private fun completionDurationString(placedSec: Long?, completedSec: Long?): StringResource? {
        if (placedSec == null || completedSec == null || completedSec <= placedSec) return null
        val totalSeconds = completedSec - placedSec
        val minutes = totalSeconds / SECONDS_PER_MINUTE
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        val text = if (minutes == 0L) "${seconds}s" else "${minutes}m${seconds}s"
        return stringRes(text)
    }

    private fun formatTerminalTimestamp(epochSeconds: Long): StringResource =
        stringRes(R.string.upi_offramp_terminal_at, formatTerminalTimestampValue(epochSeconds))

    private fun formatTerminalTimestampValue(epochSeconds: Long): String =
        terminalDateFormat.format(Date(epochSeconds * MILLIS_PER_SECOND))

    private fun displayUsdc(value: Usdc6): String = value.toDisplayString()

    // actualFiatAmount rides in the diamond's 6-decimal unit but is fiat, so render at the currency's
    // own minor-unit precision (IDR 0, INR/BRL 2), not USDC's 6dp: "Rp50000", not "Rp50000.000000".
    private fun displayFiat(value: Usdc6): String =
        value.whole.setScale(args.currency.precision, RoundingMode.FLOOR).toPlainString()

    // VEN Pago Móvil hands back the whole base64 QR blob as the "address"; ellipsize it so it doesn't
    // wrap across the recipient subtitle. Human-readable handles (VPA, merchant name) pass through.
    private fun displayRecipient(address: String): String =
        if (args.currency.paymentAddressIsOpaque) {
            address.ellipsizeMiddle(RECIPIENT_ELLIPSIS_PREFIX, RECIPIENT_ELLIPSIS_SUFFIX)
        } else {
            address
        }

    companion object {
        private const val SECONDS_PER_MINUTE = 60L
        private const val MILLIS_PER_SECOND = 1_000L
        private const val RECIPIENT_ELLIPSIS_PREFIX = 12
        private const val RECIPIENT_ELLIPSIS_SUFFIX = 8

        // Locale-stable format so screenshots / fixtures don't drift across devices.
        private val terminalDateFormat: SimpleDateFormat =
            SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }
    }
}
