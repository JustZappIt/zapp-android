// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.justzappit.evm.math.BigDecimal
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.DecimalRounding
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.math.decimalDivide
import xyz.justzappit.evm.math.decimalMovePointRight
import xyz.justzappit.evm.math.decimalSetScale
import xyz.justzappit.evm.math.decimalStripTrailingZeros
import xyz.justzappit.evm.math.decimalToBigInteger
import xyz.justzappit.evm.math.decimalToPlainString
import xyz.justzappit.evm.math.div
import xyz.justzappit.evm.math.minus
import xyz.justzappit.evm.math.plus
import xyz.justzappit.evm.math.times
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.offramp.account.SmartOfframpAccountProvider
import xyz.justzappit.offramp.config.P2pNetworkConfig
import xyz.justzappit.offramp.config.P2pNetworks
import xyz.justzappit.offramp.funding.NoRouteOfframpRefund
import xyz.justzappit.offramp.funding.NoRouteOfframpTopUp
import xyz.justzappit.offramp.funding.OfframpFunding
import xyz.justzappit.offramp.funding.OfframpRefund
import xyz.justzappit.offramp.funding.OfframpTopUp
import xyz.justzappit.offramp.funding.PreFundedOfframpFunding
import xyz.justzappit.offramp.funding.RefundResume
import xyz.justzappit.offramp.orchestrator.AaOfframpDriver
import xyz.justzappit.offramp.orchestrator.BridgeToBaseStatus
import xyz.justzappit.offramp.orchestrator.OfframpCheckpoint
import xyz.justzappit.offramp.orchestrator.OfframpPaymentDetails
import xyz.justzappit.offramp.orchestrator.OfframpPaymentDetailsProvider
import xyz.justzappit.offramp.orchestrator.OfframpRequest
import xyz.justzappit.offramp.orchestrator.OfframpStatus
import xyz.justzappit.offramp.orchestrator.OfframpStep
import xyz.justzappit.offramp.orchestrator.PlaceOrderMarkerPersistenceException
import xyz.justzappit.offramp.orchestrator.orderId
import xyz.justzappit.offramp.orchestrator.platformCurrentTimeMillis
import xyz.justzappit.offramp.orchestrator.step
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.DirectPixResolver
import xyz.justzappit.offramp.p2p.FallbackOrderReader
import xyz.justzappit.offramp.p2p.OnChainOrderReader
import xyz.justzappit.offramp.p2p.OrderStatus
import xyz.justzappit.offramp.p2p.P2pOrderHistorySource
import xyz.justzappit.offramp.p2p.P2pOrderLimits
import xyz.justzappit.offramp.p2p.PaymentQrError
import xyz.justzappit.offramp.p2p.PaymentQrParseResult
import xyz.justzappit.offramp.p2p.PaymentQrParser
import xyz.justzappit.offramp.p2p.SubgraphClient
import xyz.justzappit.offramp.p2p.SubgraphOrderReader
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.p2p.getPayFeeConfig
import xyz.justzappit.offramp.p2p.getPriceConfig
import xyz.justzappit.offramp.p2p.getUsdcBalance

/**
 * Swift-friendly facade over the shared state machine. Every amount crossing this boundary is a
 * decimal string or a 6-decimal micro-unit string; no Kotlin inline value class is exposed as Any.
 */
class AppleOfframpClient private constructor(
    private val rpc: BaseRpcClient,
    private val network: P2pNetworkConfig,
    private val smartAccountProvider: SmartOfframpAccountProvider,
    private val driver: AaOfframpDriver,
    private val historySource: P2pOrderHistorySource,
    private val dynamicPixResolver: DirectPixResolver,
    private val storage: AppleOfframpStorage,
) {
    private val json = CHECKPOINT_JSON
    private val checkpointMutationMutex = Mutex()

    val networkName: String get() = network.name
    val explorerUrl: String get() = network.baseExplorerUrl

    fun corridors(): List<ApplePaymentCorridor> = CORRIDORS

    @Throws(Exception::class)
    suspend fun accountAddress(): String = smartAccountProvider.resolve().address.checksumHex

    @Throws(Exception::class)
    suspend fun accountSummary(): AppleOfframpAccountSummary {
        val account = smartAccountProvider.resolve().address
        // Match Android's balance poll: a transient Base RPC failure must not prevent entry into
        // the payment screen. Commit-time quote/pay calls still fail closed and surface an error.
        val balance = runCatching { rpc.getUsdcBalance(network.usdcAddress, account) }.getOrNull()
        val hasRefundCheckpoint = storage.refundCheckpointJson().value != null
        return AppleOfframpAccountSummary(
            address = account.checksumHex,
            balanceMicros = balance?.micros?.toString(),
            balanceDisplay = balance?.toDisplayString(stripTrailingZeros = true),
            explorerUrl = network.addressUrl(account.checksumHex),
            canBridgeToBase = network.chainId == P2pNetworks.MAINNET_CHAIN_ID,
            canRefundToZec =
                network.chainId == P2pNetworks.MAINNET_CHAIN_ID &&
                    ((balance != null && balance > Usdc6.ZERO) || hasRefundCheckpoint),
        )
    }

    @Throws(Exception::class)
    suspend fun parsePaymentQr(currencyCode: String, rawPayload: String): ApplePaymentQrResult {
        val currency = CurrencyCode.fromCode(currencyCode)
        return when (
            val parsed =
                PaymentQrParser.parse(
                    currency = currency,
                    qrData = rawPayload,
                    dynamicPixResolver = dynamicPixResolver,
                )
        ) {
            is PaymentQrParseResult.Success -> {
                ApplePaymentQrResult(
                    isValid = true,
                    paymentAddress = parsed.parsed.paymentAddress,
                    fiatAmount = parsed.parsed.fiatAmount?.let(::decimalToPlainString),
                )
            }

            is PaymentQrParseResult.Failure -> {
                ApplePaymentQrResult(isValid = false, errorCode = parsed.error.appleCode())
            }
        }
    }

    @Throws(Exception::class)
    suspend fun quote(currencyCode: String, fiatAmount: String): AppleOfframpQuote {
        val currency = CurrencyCode.fromCode(currencyCode)
        val input = BigDecimal(fiatAmount.trim())
        require(input.signum() > 0) { "fiatAmount must be positive" }
        val snapped = decimalSetScale(input, currency.precision, DecimalRounding.DOWN)
        val price = rpc.getPriceConfig(network.diamondAddress, currency)
        val rate = price.sellPriceAsRate()
        val wholeUsdc = decimalDivide(snapped, rate, USDC_SCALE, DecimalRounding.DOWN)
        val order = Usdc6.ofWhole(wholeUsdc)
        require(order > Usdc6.ZERO) { "fiatAmount is below one micro-USDC" }
        require(order <= P2pOrderLimits.MAX_ORDER) { "Order exceeds the 100 USDC limit" }
        val feeConfig = rpc.getPayFeeConfig(network.diamondAddress, currency)
        val fee = feeConfig.feeFor(order)
        val required = order + fee
        val account = smartAccountProvider.resolve().address
        val balance = rpc.getUsdcBalance(network.usdcAddress, account)
        val shortfall = if (required > balance) required - balance else Usdc6.ZERO
        return AppleOfframpQuote(
            currencyCode = currency.code,
            fiatAmount = decimalToPlainString(snapped),
            usdcMicros = order.micros.toString(),
            usdcDisplay = order.toDisplayString(stripTrailingZeros = true),
            sellRate = decimalToPlainString(decimalStripTrailingZeros(rate)),
            fixedFeeMicros = fee.micros.toString(),
            fixedFeeDisplay = fee.toDisplayString(stripTrailingZeros = true),
            requiredBalanceMicros = required.micros.toString(),
            baseBalanceMicros = balance.micros.toString(),
            baseBalanceDisplay = balance.toDisplayString(stripTrailingZeros = true),
            shortfallMicros = shortfall.micros.toString(),
            shortfallDisplay = shortfall.toDisplayString(stripTrailingZeros = true),
            canPayFromBase = balance >= required,
            canBridgeToBase = network.chainId == P2pNetworks.MAINNET_CHAIN_ID,
        )
    }

    @Throws(Exception::class)
    suspend fun hasCheckpoint(): Boolean = storage.checkpointJson().value != null

    @Throws(Exception::class)
    suspend fun checkpointCurrencyCode(): String? =
        storage.checkpointJson().value?.let { decodeCheckpoint(it).currency.code }

    /**
     * Base USDC promised to an unfinished Scan & Pay order but potentially still present in the
     * raw account balance. Before placeOrder settles this is the quoted principal plus fee; once an
     * order id proves the principal was consumed, only the still-pending fee remains. The persisted
     * quote is authoritative so a later fee-config change cannot resize the reservation. A legacy
     * record is atomically upgraded with the fee read used for hydration before that value is
     * returned. Storage, decode, and fee-read failures throw: callers must fail closed rather than
     * treating an unreadable financial record as empty.
     */
    @Throws(Exception::class)
    suspend fun pendingBaseCommitmentMicros(): String? {
        var checkpoint = storage.checkpointJson().value?.let(::decodeCheckpoint) ?: return null
        if (!checkpoint.currentStep.holdsUnescrowedP2pCommitment) return null
        checkpoint = bindAuthorizedFee()
        val fee = requireNotNull(checkpoint.authorizedPayFee)
        return checkpoint.pendingBaseCommitment(fee)?.micros?.toString()
    }

    /** The Base USDC owned by an unfinished refund bridge, or null when none exists. */
    @Throws(Exception::class)
    suspend fun pendingRefundCommitmentMicros(): String? =
        storage
            .refundCheckpointJson()
            .value
            ?.let(::decodeRefundCheckpoint)
            ?.usdcMicros

    @Throws(Exception::class)
    suspend fun discardCheckpoint() = checkpointMutationMutex.withLock { storage.clearCheckpoint() }

    @Throws(Exception::class)
    suspend fun hasTopUpCheckpoint(): Boolean = storage.topUpCheckpointJson().value != null

    @Throws(Exception::class)
    suspend fun topUpCheckpointMicros(): String? =
        storage.topUpCheckpointJson().value?.let { decodeTopUpCheckpoint(it).usdcMicros }

    @Throws(Exception::class)
    suspend fun discardTopUpCheckpoint() = storage.clearTopUpCheckpoint()

    fun pay(
        quote: AppleOfframpQuote,
        paymentDetailsProvider: AppleOfframpPaymentDetailsProvider,
        payeeName: String? = null,
    ): Flow<AppleOfframpStatus> =
        flow {
            val currency = CurrencyCode.fromCode(quote.currencyCode)
            val fiat = Usdc6.ofWhole(BigDecimal(quote.fiatAmount))
            val rateMicros =
                decimalToBigInteger(
                    decimalSetScale(
                        decimalMovePointRight(BigDecimal(quote.sellRate), USDC_SCALE),
                        0,
                        DecimalRounding.DOWN,
                    ),
                )
            val order = usdcFromMicros(quote.usdcMicros)
            val authorizedFee = usdcFromMicros(quote.fixedFeeMicros)
            val authorizedRequired = usdcFromMicros(quote.requiredBalanceMicros)
            require(authorizedFee >= Usdc6.ZERO) { "Quoted PAY fee must be nonnegative" }
            require(authorizedRequired == order + authorizedFee) {
                "Quoted required balance must equal the order amount plus PAY fee"
            }
            val fiatLimit = Usdc6((order.micros * rateMicros) / MICROS_PER_UNIT)
            val request =
                OfframpRequest(
                    usdcAmount = order,
                    fiatAmount = fiat,
                    currency = currency,
                    payeeName = payeeName,
                    fiatAmountLimit = fiatLimit,
                    authorizedPayFee = authorizedFee,
                    authorizedRequiredBalance = authorizedRequired,
                )
            val checkpointJson = storage.checkpointJson().value
            val existing: OfframpCheckpoint? =
                if (checkpointJson == null) {
                    null
                } else {
                    decodeCheckpoint(checkpointJson)
                    bindAuthorizedFee()
                }
            // A resume belongs to the persisted request. Never let a fresh screen quote overwrite
            // its currency or amounts while checkpointing later resume statuses.
            val persistedRequest = existing?.toRequest(fallbackFiatAmount = request.fiatAmount) ?: request
            val persister = AppleCheckpointPersister(storage, persistedRequest, json, checkpointMutationMutex)
            persister.seedFrom(existing)
            val detailsProvider = paymentDetailsProvider.asSharedProvider()
            val upstream =
                if (existing != null) {
                    driver.resume(existing, detailsProvider)
                } else {
                    driver.run(persistedRequest, detailsProvider)
                }
            try {
                upstream.collect { status ->
                    persister.onStatus(status)
                    emit(status.toAppleStatus())
                }
            } catch (error: PlaceOrderMarkerPersistenceException) {
                retireFailedPlaceOrderMarker()
                throw error
            }
        }

    fun resumePayment(
        paymentDetailsProvider: AppleOfframpPaymentDetailsProvider,
    ): Flow<AppleOfframpStatus> =
        flow {
            requireNotNull(storage.checkpointJson().value?.let { decodeCheckpoint(it) }) {
                "No P2P payment is available to resume"
            }
            val checkpoint = bindAuthorizedFee()
            val persistedRequest = checkpoint.toRequest(fallbackFiatAmount = checkpoint.usdcAmount)
            val persister = AppleCheckpointPersister(storage, persistedRequest, json, checkpointMutationMutex)
            persister.seedFrom(checkpoint)
            try {
                driver.resume(checkpoint, paymentDetailsProvider.asSharedProvider()).collect { status ->
                    persister.onStatus(status)
                    emit(status.toAppleStatus())
                }
            } catch (error: PlaceOrderMarkerPersistenceException) {
                retireFailedPlaceOrderMarker()
                throw error
            }
        }

    private suspend fun retireFailedPlaceOrderMarker() {
        try {
            checkpointMutationMutex.withLock { storage.clearCheckpoint() }
        } catch (
            @Suppress("SwallowedException", "TooGenericExceptionCaught") cleanupError: Exception,
        ) {
            // The host can commit its atomic replacement and then fail while applying file
            // attributes. Repeating with an empty record retires that committed-but-unsent marker.
        }
    }

    /**
     * Migrates pre-reservation checkpoints to an immutable fee/debit pair. Persisting before use is
     * essential: returning a live fee without binding it would let the contract config change
     * between Swift's claim and setSellOrderUpi's later transferFrom.
     */
    private suspend fun bindAuthorizedFee(): OfframpCheckpoint =
        checkpointMutationMutex.withLock {
            // Another pending/resume call can advance or bind the single checkpoint while this one
            // is reading the fee. Re-read under the mutation lock and use exactly the record whose
            // binding will be returned to Swift.
            val latest =
                requireNotNull(storage.checkpointJson().value?.let(::decodeCheckpoint)) {
                    "The saved P2P payment changed while its fee was being bound"
                }
            if (latest.authorizedPayFee != null) return@withLock latest
            val fee = rpc.getPayFeeConfig(network.diamondAddress, latest.currency).feeFor(latest.usdcAmount)
            val bound =
                latest.copy(
                    authorizedPayFeeMicroDecimal = fee.micros.toString(),
                    authorizedRequiredBalanceMicroDecimal = (latest.usdcAmount + fee).micros.toString(),
                )
            storage.storeCheckpointJson(json.encodeToString(OfframpCheckpoint.serializer(), bound))
            bound
        }

    fun bridgeToBase(usdcMicros: String, resumeDepositAddress: String? = null): Flow<AppleOfframpStatus> =
        flow {
            val amount = usdcFromMicros(usdcMicros)
            require(amount > Usdc6.ZERO && amount <= P2pOrderLimits.MAX_ORDER) {
                "Base top-ups must be between one micro-USDC and 100 USDC"
            }
            val stored = storage.topUpCheckpointJson().value?.let(::decodeTopUpCheckpoint)
            if (stored != null) {
                require(stored.usdcMicros == usdcMicros) {
                    "A different Base top-up is already in progress. Resume or discard it before starting another."
                }
            }
            val resumeHandle = resumeDepositAddress ?: stored?.depositAddress
            driver.bridgeToBase(amount, resumeHandle).collect { status ->
                when (status) {
                    is BridgeToBaseStatus.Bridging -> {
                        status.depositAddress?.let { depositAddress ->
                            storage.storeTopUpCheckpointJson(
                                json.encodeToString(
                                    AppleTopUpCheckpoint.serializer(),
                                    AppleTopUpCheckpoint(
                                        usdcMicros = usdcMicros,
                                        depositAddress = depositAddress,
                                        createdAtMillis = stored?.createdAtMillis ?: platformCurrentTimeMillis(),
                                    ),
                                ),
                            )
                        }
                    }

                    is BridgeToBaseStatus.Complete -> {
                        storage.clearTopUpCheckpoint()
                    }

                    is BridgeToBaseStatus.Failed -> {
                        if (status.cause is AppleBridgeTerminalException || status.depositAddress == null) {
                            storage.clearTopUpCheckpoint()
                        }
                    }

                    BridgeToBaseStatus.Idle -> {
                        Unit
                    }
                }
                emit(status.toAppleStatus())
            }
        }

    fun recoverFunds(orderId: String?): Flow<AppleOfframpStatus> =
        flow {
            val stored = storage.refundCheckpointJson().value?.let(::decodeRefundCheckpoint)
            if (stored != null && orderId != null) {
                require(stored.orderId == null || stored.orderId == orderId) {
                    "A different refund bridge is already in progress. Resume it before recovering another order."
                }
            }
            val resume =
                stored?.let {
                    RefundResume(
                        handle = it.depositAddress,
                        amount = usdcFromMicros(it.usdcMicros),
                        transferStarted = it.transferStarted,
                        txHash = it.txHash?.let(xyz.justzappit.evm.types.TxHash::fromHex),
                    )
                }
            driver.bridgeFundsBackToZec(orderId?.let(::BigInteger), resume).collect { status ->
                when (status) {
                    is OfframpStatus.FundsRecovered -> {
                        storage.clearRefundCheckpoint()
                    }

                    is OfframpStatus.Failed -> {
                        if (status.cause is AppleBridgeTerminalException) storage.clearRefundCheckpoint()
                    }

                    else -> {
                        Unit
                    }
                }
                emit(status.toAppleStatus())
            }
        }

    @Throws(Exception::class)
    suspend fun history(): List<AppleOfframpHistoryItem> {
        val account = smartAccountProvider.resolve().address
        return historySource.fetchAll(account).map { item ->
            AppleOfframpHistoryItem(
                orderId = item.orderId.toString(),
                status = item.status.name,
                orderType = item.orderType.name,
                currencyCode = currencyCodeFromBytes32(item.currencyHex),
                usdcMicros = item.usdcAmount.micros.toString(),
                fiatMicros = item.fiatAmount.micros.toString(),
                placedAtEpochSeconds = item.placedAtEpochSeconds,
                completedAtEpochSeconds = item.completedAtEpochSeconds,
                cancelledAtEpochSeconds = item.cancelledAtEpochSeconds,
                paymentAddress = item.recipientUpiPlain,
                merchantAddress = item.acceptedMerchantAddress?.checksumHex,
                fixedFeeMicros = item.fixedFeePaid?.micros?.toString(),
            )
        }
    }

    fun transactionUrl(txHash: String): String = network.txUrl(txHash)

    private fun decodeCheckpoint(value: String): OfframpCheckpoint =
        try {
            json.decodeFromString(OfframpCheckpoint.serializer(), value)
        } catch (error: Exception) {
            throw IllegalStateException(
                "The saved P2P payment is incompatible or corrupted. Its recovery data was preserved.",
                error,
            )
        }

    private fun decodeTopUpCheckpoint(value: String): AppleTopUpCheckpoint =
        try {
            json.decodeFromString(AppleTopUpCheckpoint.serializer(), value)
        } catch (error: Exception) {
            throw IllegalStateException(
                "The saved Base top-up is incompatible or corrupted. Its recovery data was preserved.",
                error,
            )
        }

    private fun decodeRefundCheckpoint(value: String): AppleRefundCheckpoint =
        try {
            json.decodeFromString(AppleRefundCheckpoint.serializer(), value)
        } catch (error: Exception) {
            throw IllegalStateException(
                "The saved refund bridge is incompatible or corrupted. Its recovery data was preserved.",
                error,
            )
        }

    companion object {
        @Throws(Exception::class)
        suspend fun create(
            account: AppleBaseAccount,
            storage: AppleOfframpStorage,
            bridge: AppleOfframpBridge? = null,
        ): AppleOfframpClient {
            val network = account.network
            val rpc = account.rpc
            val storedCheckpoint =
                storage.checkpointJson().value?.let { value ->
                    try {
                        CHECKPOINT_JSON.decodeFromString(OfframpCheckpoint.serializer(), value)
                    } catch (error: Exception) {
                        throw IllegalStateException(
                            "The saved P2P payment is incompatible or corrupted. Its recovery data was preserved.",
                            error,
                        )
                    }
                }
            storedCheckpoint?.takeIf { it.hasUnresolvedPlaceSubmission }?.let { unresolved ->
                account.submitters
                    .resolve()
                    .submitter
                    .restorePendingTransaction(checkNotNull(unresolved.placeOrderTxHash), unresolved.placeOrderNonce)
            }
            val subgraph = SubgraphClient(account.httpClient, network.subgraphUrl)
            val relayStore = AppleRelayIdentityStore(storage)
            val recipientCache = AppleOrderRecipientCache(storage)
            val onChainReader = OnChainOrderReader(rpc, network)
            val orderReader = FallbackOrderReader(SubgraphOrderReader(subgraph), onChainReader)
            val funding: OfframpFunding
            val refund: OfframpRefund
            val topUp: OfframpTopUp
            if (network.chainId == P2pNetworks.MAINNET_CHAIN_ID) {
                val host = requireNotNull(bridge) { "Mainnet requires an AppleOfframpBridge" }
                val hostFunding = AppleBridgeFunding(rpc, network.usdcAddress, host)
                funding = hostFunding
                topUp = hostFunding
                refund =
                    AppleBridgeRefund(host) { amount, handle, transferStarted, txHash ->
                        storage.storeRefundCheckpointJson(
                            Json.encodeToString(
                                AppleRefundCheckpoint.serializer(),
                                AppleRefundCheckpoint(
                                    usdcMicros = amount.micros.toString(),
                                    depositAddress = handle,
                                    orderId = null,
                                    createdAtMillis = platformCurrentTimeMillis(),
                                    transferStarted = transferStarted,
                                    txHash = txHash,
                                ),
                            ),
                        )
                    }
            } else {
                funding = PreFundedOfframpFunding(rpc, network.usdcAddress)
                topUp = NoRouteOfframpTopUp()
                refund = NoRouteOfframpRefund()
            }
            return AppleOfframpClient(
                rpc = rpc,
                network = network,
                smartAccountProvider = account.smartAccounts,
                driver =
                    AaOfframpDriver(
                        rpc = rpc,
                        network = network,
                        submitters = account.submitters,
                        subgraph = subgraph,
                        orderReader = orderReader,
                        funding = funding,
                        refund = refund,
                        topUp = topUp,
                        relayIdentityStore = relayStore,
                        orderRecipientUpiCache = recipientCache,
                    ),
                historySource =
                    P2pOrderHistorySource(
                        subgraph = subgraph,
                        relayIdentityStore = relayStore,
                        orderRecipientUpiCache = recipientCache,
                        onChainOrderReader = onChainReader,
                        rpc = rpc,
                        network = network,
                    ),
                dynamicPixResolver = DirectPixResolver(account.httpClient),
                storage = storage,
            )
        }

        private val MICROS_PER_UNIT = bigIntegerValueOf(1_000_000L)
        private val CHECKPOINT_JSON = Json { ignoreUnknownKeys = true }
        private const val USDC_SCALE = 6

        private val CORRIDORS =
            listOf(
                ApplePaymentCorridor("INR", "India", "UPI", "🇮🇳", "₹", 2),
                ApplePaymentCorridor("BRL", "Brazil", "PIX", "🇧🇷", "R$", 2),
                ApplePaymentCorridor("IDR", "Indonesia", "QRIS", "🇮🇩", "Rp", 0),
                ApplePaymentCorridor("ARS", "Argentina", "Mercado Pago", "🇦🇷", "$", 2),
                ApplePaymentCorridor("VEN", "Venezuela", "Pago Móvil", "🇻🇪", "Bs", 2),
                ApplePaymentCorridor("NGN", "Nigeria", "NIP", "🇳🇬", "₦", 2),
                ApplePaymentCorridor("COP", "Colombia", "Transferencia", "🇨🇴", "$", 2),
            )
    }
}

private fun AppleOfframpPaymentDetailsProvider.asSharedProvider(): OfframpPaymentDetailsProvider =
    OfframpPaymentDetailsProvider { orderId, _, request ->
        val details =
            requestPaymentDetails(
                orderId = orderId.toString(),
                currencyCode = request.currency.code,
                fiatAmount = request.fiatAmount.toDisplayString(stripTrailingZeros = true),
            )
        OfframpPaymentDetails(
            rawPayload = details.rawPayload,
            paymentAddress = details.paymentAddress,
            fiatAmount = details.fiatAmount?.let(::BigDecimal),
        )
    }

private class AppleCheckpointPersister(
    private val storage: AppleOfframpStorage,
    private val request: OfframpRequest,
    private val json: Json,
    private val mutationMutex: Mutex,
) {
    private var approveHash: TxHash? = null
    private var placeHash: TxHash? = null
    private var placeNonceDecimal: String? = null
    private var bridgeHandle: String? = null

    fun seedFrom(value: OfframpCheckpoint?) {
        approveHash = value?.approveTxHash
        placeHash = value?.placeOrderTxHash
        placeNonceDecimal = value?.placeOrderNonceDecimal
        bridgeHandle = value?.bridgeDepositAddress
    }

    suspend fun onStatus(status: OfframpStatus) =
        mutationMutex.withLock {
            when (status) {
                is OfframpStatus.ApprovingUsdc -> {
                    approveHash = status.txHash
                }

                is OfframpStatus.PlacingOrder -> {
                    placeHash = status.txHash
                    status.submissionNonceDecimal?.let { placeNonceDecimal = it }
                }

                is OfframpStatus.BridgingFunds -> {
                    status.depositAddress?.let { bridgeHandle = it }
                }

                else -> {
                    Unit
                }
            }
            when (status) {
                is OfframpStatus.Completed,
                is OfframpStatus.Cancelled,
                is OfframpStatus.FundsRecovered,
                -> {
                    storage.clearCheckpoint()
                }

                is OfframpStatus.Failed -> {
                    val resumable =
                        (
                            status.step == OfframpStep.FUNDING &&
                                bridgeHandle != null &&
                                status.cause !is AppleBridgeTerminalException
                        ) ||
                            (
                                status.step == OfframpStep.PLACING_ORDER &&
                                    placeHash != null &&
                                    !status.nothingEscrowed
                            )
                    if (resumable) persist(status.orderId?.toString(), status) else storage.clearCheckpoint()
                }

                else -> {
                    val orderId = status.orderId?.toString()
                    if (orderId != null || bridgeHandle != null || placeHash != null) persist(orderId, status)
                }
            }
        }

    private suspend fun persist(orderId: String?, status: OfframpStatus) {
        val previous =
            storage.checkpointJson().value?.let {
                runCatching { json.decodeFromString(OfframpCheckpoint.serializer(), it) }.getOrNull()
            }
        val checkpoint =
            OfframpCheckpoint(
                orderId = orderId,
                currentStep = status.step,
                bridgeDepositAddress = bridgeHandle ?: previous?.bridgeDepositAddress,
                approveTxHash = approveHash ?: previous?.approveTxHash,
                placeOrderTxHash = placeHash ?: previous?.placeOrderTxHash,
                placeOrderNonceDecimal = placeNonceDecimal ?: previous?.placeOrderNonceDecimal,
                setUpiTxHash = (status as? OfframpStatus.SendingEncryptedUpi)?.txHash ?: previous?.setUpiTxHash,
                recipientUpi =
                    (status as? OfframpStatus.SendingEncryptedUpi)?.paymentAddress ?: request.recipientUpi,
                usdcAmountMicroDecimal = request.usdcAmount.micros.toString(),
                authorizedPayFeeMicroDecimal = request.authorizedPayFee?.micros?.toString(),
                authorizedRequiredBalanceMicroDecimal = request.authorizedRequiredBalance?.micros?.toString(),
                fiatAmountMicroDecimal = request.fiatAmount.micros.toString(),
                fiatAmountLimitMicroDecimal = request.fiatAmountLimit?.micros?.toString(),
                payeeName = request.payeeName,
                currency = request.currency,
                createdAtMillis = previous?.createdAtMillis ?: platformCurrentTimeMillis(),
            )
        storage.storeCheckpointJson(json.encodeToString(OfframpCheckpoint.serializer(), checkpoint))
    }
}

private fun PaymentQrError.appleCode(): String =
    when (this) {
        PaymentQrError.EmptyQr -> "empty"
        PaymentQrError.InvalidFormat -> "invalid_format"
        PaymentQrError.MissingPaymentAddress -> "missing_payment_address"
        is PaymentQrError.InvalidPaymentAddress -> "invalid_payment_address"
        PaymentQrError.InvalidChecksum -> "invalid_checksum"
        is PaymentQrError.InvalidAmount -> "invalid_amount"
        is PaymentQrError.DynamicFetchFailed -> "dynamic_fetch_failed"
        is PaymentQrError.UnsupportedCurrency -> "unsupported_currency"
    }

private val OfframpStep.holdsUnescrowedP2pCommitment: Boolean
    get() =
        when (this) {
            OfframpStep.INITIALIZATION,
            OfframpStep.SELECTING_CIRCLE,
            OfframpStep.FUNDING,
            OfframpStep.APPROVING_USDC,
            OfframpStep.PLACING_ORDER,
            OfframpStep.WAITING_FOR_ACCEPTANCE,
            OfframpStep.WAITING_FOR_PAYMENT_DETAILS,
            OfframpStep.ENCRYPTING_UPI,
            -> true

            OfframpStep.SENDING_UPI,
            OfframpStep.WAITING_FOR_COMPLETION,
            -> false
        }

internal fun OfframpCheckpoint.pendingBaseCommitment(resolvedFee: Usdc6): Usdc6? {
    if (!currentStep.holdsUnescrowedP2pCommitment) return null
    val required = authorizedRequiredBalance ?: (usdcAmount + resolvedFee)
    val commitment = if (orderIdBig == null) required else resolvedFee
    return commitment.takeIf { it > Usdc6.ZERO }
}

@Serializable
private data class AppleTopUpCheckpoint(
    val version: Int = VERSION,
    val usdcMicros: String,
    val depositAddress: String,
    val createdAtMillis: Long,
) {
    init {
        require(version == VERSION) { "Unsupported Base top-up checkpoint version $version" }
        require(usdcFromMicros(usdcMicros) > Usdc6.ZERO) { "Top-up amount must be positive" }
        require(depositAddress.isNotBlank()) { "Top-up deposit address must not be blank" }
    }

    companion object {
        private const val VERSION = 1
    }
}

@Serializable
internal data class AppleRefundCheckpoint(
    val version: Int = VERSION,
    val usdcMicros: String,
    val depositAddress: String,
    val orderId: String?,
    val createdAtMillis: Long,
    // Old v1 checkpoints were written immediately before the transfer. Defaulting to started is
    // fail-closed: an upgraded app may wait for settlement, but it must never debit unrelated
    // Base funds merely because the current balance happens to be large enough.
    val transferStarted: Boolean = true,
    val txHash: String? = null,
) {
    init {
        require(version == VERSION) { "Unsupported refund checkpoint version $version" }
        require(usdcFromMicros(usdcMicros) > Usdc6.ZERO) { "Refund amount must be positive" }
        require(depositAddress.isNotBlank()) { "Refund deposit address must not be blank" }
    }

    companion object {
        private const val VERSION = 1
    }
}

private fun OfframpStatus.toAppleStatus(): AppleOfframpStatus =
    when (this) {
        OfframpStatus.Idle -> {
            AppleOfframpStatus("idle", step.name, "Preparing payment")
        }

        is OfframpStatus.SelectingCircle -> {
            AppleOfframpStatus("selecting_circle", step.name, "Finding an available merchant")
        }

        is OfframpStatus.BridgingFunds -> {
            AppleOfframpStatus(
                "bridging_funds",
                step.name,
                "Adding funds to Base",
                bridgeDepositAddress = depositAddress,
            )
        }

        is OfframpStatus.FundedFromBase -> {
            AppleOfframpStatus("funded_from_base", step.name, "Using Base balance")
        }

        is OfframpStatus.ApprovingUsdc -> {
            AppleOfframpStatus("approving_usdc", step.name, "Approving USDC", txHash = txHash.hex)
        }

        is OfframpStatus.PlacingOrder -> {
            AppleOfframpStatus("placing_order", step.name, "Placing order", txHash = txHash.hex)
        }

        is OfframpStatus.WaitingForMerchantAcceptance -> {
            AppleOfframpStatus(
                "waiting_for_merchant",
                step.name,
                "Waiting for a merchant",
                detail = if (stalled) "This is taking longer than usual" else null,
                orderId = orderId.toString(),
            )
        }

        is OfframpStatus.WaitingForPaymentDetails -> {
            AppleOfframpStatus(
                "waiting_for_payment_details",
                step.name,
                "Merchant accepted",
                detail = "Merchant QR is ready",
                orderId = orderId.toString(),
            )
        }

        is OfframpStatus.SendingEncryptedUpi -> {
            AppleOfframpStatus(
                "sending_payment_details",
                step.name,
                "Sending encrypted payment details",
                orderId = orderId.toString(),
                txHash = txHash.hex,
            )
        }

        is OfframpStatus.WaitingForCompletion -> {
            AppleOfframpStatus(
                "waiting_for_completion",
                step.name,
                "Merchant is completing payment",
                detail = if (stalled) "This is taking longer than usual" else null,
                orderId = orderId.toString(),
            )
        }

        is OfframpStatus.Completed -> {
            AppleOfframpStatus(
                "completed",
                step.name,
                "Payment complete",
                orderId = orderId.toString(),
                isTerminal = true,
                isSuccess = true,
            )
        }

        is OfframpStatus.Cancelled -> {
            AppleOfframpStatus(
                "cancelled",
                step.name,
                "Payment cancelled",
                orderId = orderId.toString(),
                isTerminal = true,
            )
        }

        is OfframpStatus.FundsRecovered -> {
            AppleOfframpStatus(
                "funds_recovered",
                step.name,
                "Funds returned",
                txHash = txHash?.hex,
                isTerminal = true,
                isSuccess = true,
            )
        }

        is OfframpStatus.Failed -> {
            AppleOfframpStatus(
                "failed",
                step.name,
                "Payment failed",
                detail = sdkErrorMessage ?: solidityErrorString ?: message,
                orderId = orderId?.toString(),
                txHash = txHash?.hex,
                isTerminal = true,
            )
        }
    }

private fun BridgeToBaseStatus.toAppleStatus(): AppleOfframpStatus =
    when (this) {
        BridgeToBaseStatus.Idle -> {
            AppleOfframpStatus("idle", "FUNDING", "Preparing transfer")
        }

        is BridgeToBaseStatus.Bridging -> {
            AppleOfframpStatus(
                "bridging_funds",
                "FUNDING",
                "Adding funds to Base",
                bridgeDepositAddress = depositAddress,
            )
        }

        is BridgeToBaseStatus.Complete -> {
            AppleOfframpStatus(
                "completed",
                "FUNDING",
                "Funds added to Base",
                isTerminal = true,
                isSuccess = true,
            )
        }

        is BridgeToBaseStatus.Failed -> {
            AppleOfframpStatus(
                "failed",
                "FUNDING",
                "Could not add funds",
                detail = message,
                bridgeDepositAddress = depositAddress,
                isTerminal = true,
            )
        }
    }

private fun currencyCodeFromBytes32(value: String): String =
    runCatching {
        value
            .removePrefix("0x")
            .hexToBytes()
            .takeWhile { it != 0.toByte() }
            .toByteArray()
            .decodeToString()
    }.getOrDefault("")
