// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.justzappit.evm.hd.EvmKey
import xyz.justzappit.evm.hd.EvmKeyDerivation
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
import xyz.justzappit.evm.rpc.BundlerClient
import xyz.justzappit.evm.rpc.RpcHttpClient
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.offramp.account.Erc4337SubmitterProvider
import xyz.justzappit.offramp.account.OfframpAccountProvider
import xyz.justzappit.offramp.account.SmartOfframpAccountProvider
import xyz.justzappit.offramp.config.P2pConfigProvider
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
    private val httpClient: HttpClient,
    private val rpc: BaseRpcClient,
    private val network: P2pNetworkConfig,
    private val smartAccountProvider: SmartOfframpAccountProvider,
    private val driver: AaOfframpDriver,
    private val historySource: P2pOrderHistorySource,
    private val dynamicPixResolver: DirectPixResolver,
    private val storage: AppleOfframpStorage,
    private val owner: EvmKey,
) {
    private val json = Json { ignoreUnknownKeys = true }

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

    @Throws(Exception::class)
    suspend fun discardCheckpoint() = storage.clearCheckpoint()

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
            val fiatLimit = Usdc6((order.micros * rateMicros) / MICROS_PER_UNIT)
            val request =
                OfframpRequest(
                    usdcAmount = order,
                    fiatAmount = fiat,
                    currency = currency,
                    payeeName = payeeName,
                    fiatAmountLimit = fiatLimit,
                )
            val checkpointJson = storage.checkpointJson().value
            val existing: OfframpCheckpoint? =
                if (checkpointJson == null) null else decodeCheckpoint(checkpointJson)
            // A resume belongs to the persisted request. Never let a fresh screen quote overwrite
            // its currency or amounts while checkpointing later resume statuses.
            val persistedRequest = existing?.toRequest(fallbackFiatAmount = request.fiatAmount) ?: request
            val persister = AppleCheckpointPersister(storage, persistedRequest, json)
            persister.seedFrom(existing)
            val detailsProvider = paymentDetailsProvider.asSharedProvider()
            val upstream =
                if (existing?.orderIdBig != null || existing?.bridgeDepositAddress != null) {
                    driver.resume(checkNotNull(existing), detailsProvider)
                } else {
                    driver.run(request, detailsProvider)
                }
            upstream.collect { status ->
                persister.onStatus(status)
                emit(status.toAppleStatus())
            }
        }

    fun resumePayment(
        paymentDetailsProvider: AppleOfframpPaymentDetailsProvider,
    ): Flow<AppleOfframpStatus> =
        flow {
            val checkpoint =
                requireNotNull(storage.checkpointJson().value?.let { decodeCheckpoint(it) }) {
                    "No P2P payment is available to resume"
                }
            val persistedRequest = checkpoint.toRequest(fallbackFiatAmount = checkpoint.usdcAmount)
            val persister = AppleCheckpointPersister(storage, persistedRequest, json)
            persister.seedFrom(checkpoint)
            driver.resume(checkpoint, paymentDetailsProvider.asSharedProvider()).collect { status ->
                persister.onStatus(status)
                emit(status.toAppleStatus())
            }
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

    fun close() {
        owner.zeroize()
        httpClient.close()
    }

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
            networkName: String,
            seedPhrase: String,
            pimlicoApiKey: String,
            storage: AppleOfframpStorage,
            bridge: AppleOfframpBridge? = null,
            rpcUrl: String? = null,
            subgraphUrl: String? = null,
            sponsorshipPolicyId: String? = null,
        ): AppleOfframpClient {
            require(seedPhrase.isNotBlank()) { "seedPhrase must not be blank" }
            // Use the same production client defaults as Android. A bare native Ktor client has
            // no ContentNegotiation plugin, so JsonObject RPC/subgraph request bodies fail before
            // reaching the network.
            val http = RpcHttpClient.create()
            try {
                val network = P2pConfigProvider(networkName, rpcUrl, subgraphUrl).current()
                val rpc = BaseRpcClient(http, network.rpcUrl)
                val subgraph = SubgraphClient(http, network.subgraphUrl)
                val mnemonic = seedPhrase.toCharArray()
                val owner =
                    try {
                        // Keep this explicit and symmetric with Android's
                        // StaticOfframpAccountProvider fixedAccountIndex default. The same Zcash
                        // wallet mnemonic must always recover the same Base owner at
                        // m/44'/60'/0'/0/0 on both platforms.
                        EvmKeyDerivation.derive(mnemonic, accountIndex = 0)
                    } finally {
                        mnemonic.fill('\u0000')
                    }
                val accountProvider =
                    object : OfframpAccountProvider {
                        override suspend fun nextOfframpAccount() = owner
                    }
                val smartAccount = SmartOfframpAccountProvider(accountProvider, rpc, network.accountFactoryAddress)
                val relayStore = AppleRelayIdentityStore(storage)
                val recipientCache = AppleOrderRecipientCache(storage)
                val onChainReader = OnChainOrderReader(rpc, network)
                val subgraphReader = SubgraphOrderReader(subgraph)
                val orderReader = FallbackOrderReader(subgraphReader, onChainReader)
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
                val bundler =
                    BundlerClient(
                        httpClient = http,
                        bundlerUrl = BundlerClient.urlFor(network.chainId, pimlicoApiKey),
                        entryPoint = network.entryPointAddress,
                        chainId = network.chainId,
                        sponsorshipPolicyId = sponsorshipPolicyId?.takeIf { it.isNotBlank() },
                    )
                val driver =
                    AaOfframpDriver(
                        rpc = rpc,
                        network = network,
                        submitters =
                            Erc4337SubmitterProvider(
                                rpc = rpc,
                                bundler = bundler,
                                network = network,
                                accountProvider = smartAccount,
                            ),
                        subgraph = subgraph,
                        orderReader = orderReader,
                        funding = funding,
                        refund = refund,
                        topUp = topUp,
                        relayIdentityStore = relayStore,
                        orderRecipientUpiCache = recipientCache,
                    )
                val history =
                    P2pOrderHistorySource(
                        subgraph = subgraph,
                        relayIdentityStore = relayStore,
                        orderRecipientUpiCache = recipientCache,
                        onChainOrderReader = onChainReader,
                        rpc = rpc,
                        network = network,
                    )
                return AppleOfframpClient(
                    httpClient = http,
                    rpc = rpc,
                    network = network,
                    smartAccountProvider = smartAccount,
                    driver = driver,
                    historySource = history,
                    dynamicPixResolver = DirectPixResolver(http),
                    storage = storage,
                    owner = owner,
                )
            } catch (error: Throwable) {
                http.close()
                throw error
            }
        }

        private val MICROS_PER_UNIT = bigIntegerValueOf(1_000_000L)
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
) {
    private var approveHash: TxHash? = null
    private var placeHash: TxHash? = null
    private var bridgeHandle: String? = null

    fun seedFrom(value: OfframpCheckpoint?) {
        approveHash = value?.approveTxHash
        placeHash = value?.placeOrderTxHash
        bridgeHandle = value?.bridgeDepositAddress
    }

    suspend fun onStatus(status: OfframpStatus) {
        when (status) {
            is OfframpStatus.ApprovingUsdc -> approveHash = status.txHash
            is OfframpStatus.PlacingOrder -> placeHash = status.txHash
            is OfframpStatus.BridgingFunds -> status.depositAddress?.let { bridgeHandle = it }
            else -> Unit
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
                    status.step == OfframpStep.FUNDING &&
                        bridgeHandle != null &&
                        status.cause !is AppleBridgeTerminalException
                if (resumable) persist(status.orderId?.toString(), status) else storage.clearCheckpoint()
            }

            else -> {
                val orderId = status.orderId?.toString()
                if (orderId != null || bridgeHandle != null) persist(orderId, status)
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
                setUpiTxHash = (status as? OfframpStatus.SendingEncryptedUpi)?.txHash ?: previous?.setUpiTxHash,
                recipientUpi =
                    (status as? OfframpStatus.SendingEncryptedUpi)?.paymentAddress ?: request.recipientUpi,
                usdcAmountMicroDecimal = request.usdcAmount.micros.toString(),
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
