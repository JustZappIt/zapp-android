// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import xyz.justzappit.evm.math.BigDecimal
import xyz.justzappit.evm.math.decimalToPlainString
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.account.SmartOfframpAccountProvider
import xyz.justzappit.offramp.config.P2pNetworkConfig
import xyz.justzappit.offramp.onramp.CustodialOnrampClient
import xyz.justzappit.offramp.onramp.CustodialOnrampDriver
import xyz.justzappit.offramp.onramp.Erc4337OnrampZecTransferGateway
import xyz.justzappit.offramp.onramp.FakeOnrampZecDeliveryDriver
import xyz.justzappit.offramp.onramp.FundsLocation
import xyz.justzappit.offramp.onramp.NearOnrampZecDeliveryDriver
import xyz.justzappit.offramp.onramp.NoRouteOnrampZecDeliveryDriver
import xyz.justzappit.offramp.onramp.OnrampCheckpoint
import xyz.justzappit.offramp.onramp.OnrampDestination
import xyz.justzappit.offramp.onramp.OnrampDeviceSignals
import xyz.justzappit.offramp.onramp.OnrampDeviceSignalsProvider
import xyz.justzappit.offramp.onramp.OnrampFailureCode
import xyz.justzappit.offramp.onramp.OnrampIntentAmount
import xyz.justzappit.offramp.onramp.OnrampPaymentInstruction
import xyz.justzappit.offramp.onramp.OnrampPhase
import xyz.justzappit.offramp.onramp.OnrampQuote
import xyz.justzappit.offramp.onramp.OnrampRecipientProvider
import xyz.justzappit.offramp.onramp.OnrampRequestSigner
import xyz.justzappit.offramp.onramp.OnrampSignerProvider
import xyz.justzappit.offramp.onramp.OnrampStatus
import xyz.justzappit.offramp.onramp.OnrampUsdcBalanceReader
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryCheckpoint
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryCheckpointStore
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryDriver
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryPhase
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryStatus
import xyz.justzappit.offramp.onramp.OnrampZecSwapGateway
import xyz.justzappit.offramp.onramp.OnrampZecSwapResult
import xyz.justzappit.offramp.onramp.SwapStatus
import xyz.justzappit.offramp.onramp.ValidatedZecSwapQuote
import xyz.justzappit.offramp.onramp.ZEC_QUOTE_EXPIRY_MARGIN_MILLIS
import xyz.justzappit.offramp.onramp.costBasisPoints
import xyz.justzappit.offramp.onramp.fundsLocation
import xyz.justzappit.offramp.onramp.id
import xyz.justzappit.offramp.onramp.isTerminal
import xyz.justzappit.offramp.onramp.leavesOrderAlive
import xyz.justzappit.offramp.onramp.onrampDeliveryFailure
import xyz.justzappit.offramp.onramp.orderId
import xyz.justzappit.offramp.onramp.phase
import xyz.justzappit.offramp.onramp.restartedAfterRefund
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.p2p.getUsdcBalance
import kotlin.time.Clock

/** Swift-friendly facade over the shared custodial on-ramp and durable ZEC delivery drivers. */
@Suppress("TooManyFunctions") // The facade mirrors the complete order and delivery protocol surfaces for Swift.
class AppleOnrampClient private constructor(
    private val network: P2pNetworkConfig,
    private val smartAccountProvider: SmartOfframpAccountProvider,
    private val driver: CustodialOnrampDriver,
    private val deliveryDriver: OnrampZecDeliveryDriver,
    private val swapGateway: OnrampZecSwapGateway?,
    private val checkpoints: AppleOnrampCheckpointStore,
) {
    val networkName: String get() = network.name
    val explorerUrl: String get() = network.baseExplorerUrl
    val canDeliverToZec: Boolean get() = swapGateway != null

    @Throws(Exception::class)
    suspend fun limits(currencyCode: String): AppleOnrampLimits {
        val value = driver.limits(CurrencyCode.fromCode(currencyCode))
        return AppleOnrampLimits(
            enabled = value.enabled,
            currencyCode = value.currency.code,
            minimumFiatMicros = value.minFiat.micros.toString(),
            maximumFiatMicros = value.maxFiat.micros.toString(),
            dailyFiatMicros = value.perUserDailyFiat.micros.toString(),
        )
    }

    @Throws(Exception::class)
    suspend fun recipientAddress(): String = driver.recipientAddress().checksumHex

    @Throws(Exception::class)
    suspend fun quote(fiatMicros: String, currencyCode: String): AppleOnrampQuote =
        driver.quote(usdcFromMicros(fiatMicros), CurrencyCode.fromCode(currencyCode)).toApple()

    @Throws(Exception::class)
    suspend fun estimateToZec(accountAddress: String, usdcMicros: String): AppleOnrampZecEstimate {
        val gateway = requireNotNull(swapGateway) { "ZEC delivery is unavailable" }
        val value = gateway.quote(Address.parse(accountAddress), usdcFromMicros(usdcMicros))
        return value.toApple()
    }

    fun start(
        quote: AppleOnrampQuote,
        destination: String,
        zecEstimate: AppleOnrampZecEstimate? = null,
    ): Flow<AppleOnrampStatus> =
        statusFlow {
            val nativeQuote = quote.toShared()
            val nativeDestination = OnrampDestination.valueOf(destination.uppercase())
            require(nativeDestination != OnrampDestination.ZCASH || zecEstimate != null) {
                "A validated ZEC estimate is required before placing a ZEC order"
            }
            val account = smartAccountProvider.resolve().address
            val persister = AppleOnrampPersister(checkpoints, nativeDestination, nativeQuote, account, zecEstimate)
            driver.start(nativeQuote).collect { status ->
                persister.onStatus(status)
                emit(status.toApple())
            }
        }

    fun confirmPaid(): Flow<AppleOnrampStatus> = persistedFlow(driver::confirmPaid)

    fun resume(): Flow<AppleOnrampStatus> = persistedFlow(driver::resume)

    fun cancel(): Flow<AppleOnrampStatus> = persistedFlow(driver::cancel)

    fun deliverToZec(orderId: String, recipient: String, usdcMicros: String): Flow<AppleOnrampDeliveryStatus> =
        deliveryFlow {
            val checkpoint = requireNotNull(checkpoints.getOrNull()) { "No on-ramp order is available" }
            require(checkpoint.id == orderId) { "On-ramp checkpoint belongs to another order" }
            deliveryDriver
                .deliver(
                    orderId = orderId,
                    recipient = Address.parse(recipient),
                    amount = usdcFromMicros(usdcMicros),
                    resume = checkpoint.zecDelivery,
                ).collect { emit(it.toApple()) }
        }

    /** Picks the recorded delivery back up. A confirmed refund is replayed here, never respent. */
    fun resumeDelivery(): Flow<AppleOnrampDeliveryStatus> = continueDelivery(restartAfterRefund = false)

    /** The user's explicit "convert again". Only this may spend a refund back into the swap. */
    fun retryDelivery(): Flow<AppleOnrampDeliveryStatus> = continueDelivery(restartAfterRefund = true)

    @Throws(Exception::class)
    suspend fun checkpoint(): AppleOnrampCheckpoint? = checkpoints.getOrNull()?.toApple()

    /**
     * USDC an unfinished ZEC delivery still owns on Base, including a transfer whose broadcast
     * outcome is ambiguous. Once a Base receipt proves the funds reached NEAR, or delivery/refund
     * is terminal, the raw Base balance is authoritative and this returns null. Decode/I/O errors
     * propagate so reservation hydration fails closed.
     */
    @Throws(Exception::class)
    suspend fun pendingBaseCommitmentMicros(): String? =
        checkpoints.getOrNull()?.zecDelivery?.pendingBaseCommitmentMicros

    @Throws(Exception::class)
    suspend fun clearCheckpoint() = checkpoints.clear()

    @Throws(Exception::class)
    suspend fun declaredAmountDisagrees(
        currencyCode: String,
        instructionKind: String,
        payload: String,
        expectedMicros: String,
    ): Boolean =
        OnrampIntentAmount.disagreesWith(
            currency = CurrencyCode.fromCode(currencyCode),
            instruction = instructionKind.toInstruction(payload),
            expected = usdcFromMicros(expectedMicros),
        )

    fun transactionUrl(txHash: String): String = network.txUrl(txHash)

    fun addressUrl(address: String): String = network.addressUrl(address)

    private fun persistedFlow(
        operation: (OnrampCheckpoint) -> Flow<OnrampStatus>,
    ): Flow<AppleOnrampStatus> =
        statusFlow {
            val checkpoint = requireNotNull(checkpoints.getOrNull()) { "No on-ramp order is available" }
            val persister = AppleOnrampPersister(checkpoints, checkpoint.destination)
            operation(checkpoint).collect { status ->
                persister.onStatus(status)
                emit(status.toApple())
            }
        }

    private fun continueDelivery(restartAfterRefund: Boolean): Flow<AppleOnrampDeliveryStatus> =
        deliveryFlow {
            val stored = requireNotNull(checkpoints.getOrNull()) { "No ZEC delivery is available" }
            val recorded = requireNotNull(stored.zecDelivery) { "No ZEC delivery is available" }
            val delivery =
                if (restartAfterRefund && recorded.phase == OnrampZecDeliveryPhase.REFUNDED_TO_BASE) {
                    recorded.restartedAfterRefund().also { checkpoints.store(stored.copy(zecDelivery = it)) }
                } else {
                    recorded
                }
            deliveryDriver
                .deliver(
                    orderId = stored.id,
                    recipient = Address.parse(delivery.baseAccount),
                    amount = usdcFromMicros(delivery.usdcMicros),
                    resume = delivery,
                ).collect { emit(it.toApple()) }
        }

    /**
     * Swift's Kotlin flow bridge cannot carry an exception, so a flow that fails outside the
     * driver's own handling would reach the reducer as an ordinary end of stream. Report it as the
     * transient failure it is instead — NETWORK_UNAVAILABLE leaves the order, and its resume
     * checkpoint, alive — and describe it from the checkpoint rather than from the exception.
     */
    private fun statusFlow(
        block: suspend FlowCollector<AppleOnrampStatus>.() -> Unit,
    ): Flow<AppleOnrampStatus> =
        flow {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (
                @Suppress("SwallowedException", "TooGenericExceptionCaught") error: Throwable,
            ) {
                val checkpoint = runCatching { checkpoints.getOrNull() }.getOrNull()
                emit(
                    OnrampStatus
                        .Failed(
                            code = OnrampFailureCode.NETWORK_UNAVAILABLE,
                            phase = checkpoint?.phase ?: OnrampPhase.PLACING,
                            id = checkpoint?.id,
                            orderId = checkpoint?.orderId,
                        ).toApple(),
                )
            }
        }

    /**
     * The same bridge limit for the delivery leg, where the disposition of funds may never be
     * inferred from an exception: only the durable checkpoint knows whether a transfer started.
     */
    private fun deliveryFlow(
        block: suspend FlowCollector<AppleOnrampDeliveryStatus>.() -> Unit,
    ): Flow<AppleOnrampDeliveryStatus> =
        flow {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (
                @Suppress("SwallowedException", "TooGenericExceptionCaught") error: Throwable,
            ) {
                val latest = runCatching { checkpoints.getOrNull() }.getOrNull()?.zecDelivery
                emit(onrampDeliveryFailure(latest).toApple())
            }
        }

    companion object {
        @Throws(Exception::class)
        @Suppress("LongParameterList")
        suspend fun create(
            account: AppleBaseAccount,
            onrampBaseUrl: String,
            storage: AppleOnrampStorage,
            deviceSignals: AppleOnrampDeviceSignals,
            onrampAppId: String = OnrampRequestSigner.DEFAULT_APP_ID,
            swapGateway: AppleOnrampZecSwapGateway? = null,
            useFakeDeliveryDriver: Boolean = false,
        ): AppleOnrampClient {
            require(onrampBaseUrl.isNotBlank()) { "onrampBaseUrl must not be blank" }
            val network = account.network
            val checkpoints = AppleOnrampCheckpointStore(storage)
            val swap = swapGateway?.let(::AppleOnrampSwapGatewayAdapter)
            val delivery =
                when {
                    useFakeDeliveryDriver -> {
                        FakeOnrampZecDeliveryDriver()
                    }

                    swap != null -> {
                        NearOnrampZecDeliveryDriver(
                            transfer =
                                Erc4337OnrampZecTransferGateway(
                                    usdc = network.usdcAddress,
                                    accountResolver = account.submitters::resolve,
                                    balanceReader =
                                        OnrampUsdcBalanceReader {
                                            account.rpc.getUsdcBalance(network.usdcAddress, it)
                                        },
                                ),
                            swap = swap,
                            checkpoints = checkpoints,
                        )
                    }

                    else -> {
                        NoRouteOnrampZecDeliveryDriver()
                    }
                }
            val client =
                CustodialOnrampClient(
                    httpClient = account.httpClient,
                    baseUrl = onrampBaseUrl,
                    signerProvider = OnrampSignerProvider { OnrampRequestSigner(account.owner, onrampAppId) },
                    appId = onrampAppId,
                )
            return AppleOnrampClient(
                network = network,
                smartAccountProvider = account.smartAccounts,
                driver =
                    CustodialOnrampDriver(
                        client = client,
                        deviceSignals = OnrampDeviceSignalsProvider { deviceSignals.collect().toShared() },
                        recipientProvider = OnrampRecipientProvider { account.smartAccounts.resolve().address },
                    ),
                deliveryDriver = delivery,
                swapGateway = swap,
                checkpoints = checkpoints,
            )
        }
    }
}

private class AppleOnrampCheckpointStore(
    private val storage: AppleOnrampStorage,
) : OnrampZecDeliveryCheckpointStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    /**
     * Only an absent value means "no order". A checkpoint this build cannot read is still the
     * recovery authority for funds that may already have settled, so the failure has to surface
     * rather than open a fresh Buy screen whose next order would overwrite it.
     */
    suspend fun getOrNull(): OnrampCheckpoint? = storage.checkpointJson().value?.let(::decode)

    private fun decode(value: String): OnrampCheckpoint =
        try {
            json.decodeFromString(OnrampCheckpoint.serializer(), value)
        } catch (
            @Suppress("TooGenericExceptionCaught") error: Exception,
        ) {
            throw IllegalStateException(
                "The saved Buy order is incompatible or corrupted. Its recovery data was preserved.",
                error,
            )
        }

    suspend fun store(checkpoint: OnrampCheckpoint) {
        storage.storeCheckpointJson(json.encodeToString(OnrampCheckpoint.serializer(), checkpoint))
    }

    suspend fun clear() = storage.clearCheckpoint()

    override suspend fun save(orderId: String, checkpoint: OnrampZecDeliveryCheckpoint) {
        mutex.withLock {
            val parent = checkNotNull(getOrNull()) { "On-ramp checkpoint is missing" }
            require(parent.id == orderId) { "On-ramp checkpoint belongs to another order" }
            require(parent.phase == OnrampPhase.COMPLETED) { "P2P settlement is not complete" }
            require(parent.destination == OnrampDestination.ZCASH) { "On-ramp destination is not Zcash" }
            store(parent.copy(zecDelivery = checkpoint))
        }
    }
}

private class AppleOnrampPersister(
    private val checkpoints: AppleOnrampCheckpointStore,
    private val selectedDestination: OnrampDestination,
    private val quote: OnrampQuote? = null,
    private val recipient: Address? = null,
    private val estimate: AppleOnrampZecEstimate? = null,
) {
    suspend fun onStatus(status: OnrampStatus) {
        val id = status.id ?: return
        if (
            status is OnrampStatus.Cancelled ||
            (status is OnrampStatus.Failed && !status.leavesOrderAlive)
        ) {
            checkpoints.clear()
            return
        }
        val previous = checkpoints.getOrNull()
        val destination = previous?.destination ?: selectedDestination
        val delivery =
            when {
                previous?.zecDelivery != null -> {
                    previous.zecDelivery
                }

                destination != OnrampDestination.ZCASH -> {
                    null
                }

                estimate != null && quote != null && recipient != null -> {
                    OnrampZecDeliveryCheckpoint(
                        phase = OnrampZecDeliveryPhase.QUOTE_READY,
                        usdcMicros = quote.netUsdc.micros.toString(),
                        baseAccount = recipient.checksumHex,
                        zcashRecipient = estimate.zcashRecipient,
                        depositAddress = estimate.depositAddress,
                        quoteDeadlineMillis = estimate.deadlineMillis,
                        acceptedCostBps = estimate.costBasisPoints,
                    )
                }

                status is OnrampStatus.Completed -> {
                    OnrampZecDeliveryCheckpoint(
                        phase = OnrampZecDeliveryPhase.FUNDS_ON_BASE,
                        usdcMicros = status.netUsdc.micros.toString(),
                        baseAccount = status.recipientAddress.checksumHex,
                    )
                }

                else -> {
                    null
                }
            }
        checkpoints.store(
            OnrampCheckpoint(
                id = id,
                phase = status.phase,
                orderId = status.orderId ?: previous?.orderId,
                destination = destination,
                zecDelivery = delivery,
            ),
        )
    }
}

private class AppleOnrampSwapGatewayAdapter(
    private val host: AppleOnrampZecSwapGateway,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : OnrampZecSwapGateway {
    override suspend fun quote(account: Address, amount: Usdc6): ValidatedZecSwapQuote {
        val value = host.quote(account.checksumHex, amount.micros.toString())
        require(value.mode.equals(EXACT_INPUT, ignoreCase = true)) { "Swap quote is not exact-input" }
        require(usdcFromMicros(value.inputUsdcMicros) == amount) { "Swap quote amount mismatch" }
        require(Address.parseOrNull(value.refundAddress) == account) { "Swap quote refund address mismatch" }
        require(value.recipientAddress.isNotBlank()) { "Swap quote recipient is missing" }
        require(value.destinationAddress == value.recipientAddress) { "Swap quote destination mismatch" }
        val deposit = requireNotNull(Address.parseOrNull(value.depositAddress)) { "Swap deposit address is invalid" }
        val output = BigDecimal(value.outputZec)
        val inputUsd = BigDecimal(value.inputUsd)
        val outputUsd = BigDecimal(value.outputUsd)
        require(output.signum() > 0) { "Swap quote has non-positive ZEC output" }
        require(inputUsd.signum() > 0) { "Swap quote has non-positive input value" }
        require(outputUsd.signum() > 0) { "Swap quote has non-positive output value" }
        require(BigDecimal(value.slippagePercent).compareTo(ONE_PERCENT) == 0) { "Swap quote slippage mismatch" }
        require(value.deadlineMillis > nowMillis() + ZEC_QUOTE_EXPIRY_MARGIN_MILLIS) {
            "Swap quote does not leave enough time to submit safely"
        }
        return ValidatedZecSwapQuote(
            depositAddress = deposit,
            zcashRecipient = value.recipientAddress,
            deadlineMillis = value.deadlineMillis,
            outputZec = decimalToPlainString(output),
            inputUsd = inputUsd,
            outputUsd = outputUsd,
        )
    }

    override suspend fun notifyDeposit(baseTransactionHash: String, depositAddress: Address) {
        host.notifyDeposit(baseTransactionHash, depositAddress.checksumHex)
    }

    override suspend fun status(checkpoint: OnrampZecDeliveryCheckpoint): OnrampZecSwapResult {
        val value = host.status(checkpoint.depositAddress.orEmpty())
        require(value.mode.equals(EXACT_INPUT, ignoreCase = true)) { "Swap status is not exact-input" }
        require(value.inputUsdcMicros == checkpoint.usdcMicros) { "Swap status amount mismatch" }
        val expectedRefundAddress = Address.parse(checkpoint.baseAccount).checksumHex
        require(Address.parseOrNull(value.refundAddress)?.checksumHex == expectedRefundAddress) {
            "Swap status refund address mismatch"
        }
        require(value.destinationAddress == checkpoint.zcashRecipient) { "Swap status destination mismatch" }
        val expectedDepositAddress = Address.parse(checkpoint.depositAddress.orEmpty()).checksumHex
        require(Address.parseOrNull(value.depositAddress)?.checksumHex == expectedDepositAddress) {
            "Swap status deposit address mismatch"
        }
        val status =
            SwapStatus.entries.firstOrNull { it.name.equals(value.status, ignoreCase = true) }
                ?: error("Unknown swap status")
        if (status == SwapStatus.SUCCESS) {
            require(BigDecimal(value.outputZec).signum() > 0) {
                "Swap status has non-positive ZEC output"
            }
        }
        val refunded =
            if (status == SwapStatus.REFUNDED) {
                val amount = usdcFromMicros(requireNotNull(value.refundedUsdcMicros))
                require(amount > Usdc6.ZERO) { "Refunded swap has a non-positive USDC amount" }
                require(amount <= usdcFromMicros(checkpoint.usdcMicros)) { "Refunded swap amount exceeds its input" }
                amount
            } else {
                null
            }
        return OnrampZecSwapResult(status, value.outputZec, refunded)
    }

    private companion object {
        const val EXACT_INPUT = "EXACT_INPUT"
        val ONE_PERCENT = BigDecimal("1")
    }
}

private fun AppleOnrampDeviceSignalsRecord.toShared() =
    OnrampDeviceSignals(
        userAgent = userAgent,
        platform = platform,
        language = language,
        languages = languages,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        devicePixelRatio = devicePixelRatio,
        timezone = timezone,
        timezoneOffset = timezoneOffset,
        cookiesEnabled = cookiesEnabled,
        doNotTrack = doNotTrack,
        online = online,
        touchSupport = touchSupport,
        maxTouchPoints = maxTouchPoints,
        vendor = vendor,
        appVersion = appVersion,
        colorDepth = colorDepth,
        pixelDepth = pixelDepth,
        connectionType = connectionType,
        deviceMemory = deviceMemory,
        hardwareConcurrency = hardwareConcurrency,
        seonSession = seonSession,
    )

private fun OnrampQuote.toApple() =
    AppleOnrampQuote(
        quoteId = quoteId,
        currencyCode = currency.code,
        fiatMicros = fiatAmount.micros.toString(),
        grossUsdcMicros = grossUsdc.micros.toString(),
        feeUsdcMicros = feeUsdc.micros.toString(),
        netUsdcMicros = netUsdc.micros.toString(),
        buyPriceMicros = buyPrice.micros.toString(),
        expiresAtMillis = expiresAtMillis,
    )

private fun AppleOnrampQuote.toShared() =
    OnrampQuote(
        quoteId = quoteId,
        currency = CurrencyCode.fromCode(currencyCode),
        fiatAmount = usdcFromMicros(fiatMicros),
        grossUsdc = usdcFromMicros(grossUsdcMicros),
        feeUsdc = usdcFromMicros(feeUsdcMicros),
        netUsdc = usdcFromMicros(netUsdcMicros),
        buyPrice = usdcFromMicros(buyPriceMicros),
        expiresAtMillis = expiresAtMillis,
    )

private fun ValidatedZecSwapQuote.toApple() =
    AppleOnrampZecEstimate(
        depositAddress = depositAddress.checksumHex,
        zcashRecipient = zcashRecipient,
        deadlineMillis = deadlineMillis,
        outputZec = outputZec,
        inputUsd = decimalToPlainString(inputUsd),
        outputUsd = decimalToPlainString(outputUsd),
        costBasisPoints = costBasisPoints,
    )

private fun OnrampStatus.toApple(): AppleOnrampStatus {
    val payment = this as? OnrampStatus.AwaitingPayment
    val completed = this as? OnrampStatus.Completed
    val instruction = payment?.instruction
    return AppleOnrampStatus(
        kind =
            when (this) {
                OnrampStatus.Idle -> "idle"
                OnrampStatus.Quoting -> "quoting"
                is OnrampStatus.Placing -> "placing"
                is OnrampStatus.AwaitingMerchant -> "awaitingMerchant"
                is OnrampStatus.AwaitingPayment -> "awaitingPayment"
                is OnrampStatus.ConfirmingPaid -> "confirmingPaid"
                is OnrampStatus.AwaitingSettlement -> "awaitingSettlement"
                is OnrampStatus.Completed -> "completed"
                is OnrampStatus.Cancelled -> "cancelled"
                is OnrampStatus.Failed -> "failed"
            },
        phase = phase.name,
        id = id,
        orderId = orderId,
        failureCode = (this as? OnrampStatus.Failed)?.code?.name,
        instructionKind = instruction?.kind,
        instructionAddress = instruction?.address,
        instructionPayload = instruction?.payload,
        instructionFields =
            (instruction as? OnrampPaymentInstruction.Fields)
                ?.fields
                ?.map {
                    AppleOnrampField(it.label ?: it.kind?.name.orEmpty(), it.value)
                }.orEmpty(),
        fiatMicros = (payment?.fiatAmount ?: completed?.fiatAmount)?.micros?.toString(),
        netUsdcMicros = completed?.netUsdc?.micros?.toString(),
        recipientAddress = completed?.recipientAddress?.checksumHex,
        paidTx = completed?.paidTx,
        expiresAtMillis = payment?.expiresAtMillis,
        isTerminal = isTerminal,
    )
}

private val OnrampPaymentInstruction.kind: String
    get() =
        when (this) {
            is OnrampPaymentInstruction.Upi -> "upi"
            is OnrampPaymentInstruction.Qr -> "qr"
            is OnrampPaymentInstruction.Fields -> "fields"
            is OnrampPaymentInstruction.Plain -> "plain"
        }

private val OnrampPaymentInstruction.address: String?
    get() =
        when (this) {
            is OnrampPaymentInstruction.Upi -> address
            is OnrampPaymentInstruction.Plain -> address
            else -> null
        }

private val OnrampPaymentInstruction.payload: String?
    get() =
        when (this) {
            is OnrampPaymentInstruction.Upi -> intentUrl
            is OnrampPaymentInstruction.Qr -> payload
            is OnrampPaymentInstruction.Fields -> qrPayload
            else -> null
        }

private fun OnrampZecDeliveryStatus.toApple(): AppleOnrampDeliveryStatus =
    when (this) {
        is OnrampZecDeliveryStatus.Preparing -> {
            AppleOnrampDeliveryStatus("preparing", OnrampZecDeliveryPhase.QUOTING.name, usdc.micros.toString())
        }

        is OnrampZecDeliveryStatus.Submitting -> {
            AppleOnrampDeliveryStatus(
                "submitting",
                OnrampZecDeliveryPhase.TRANSFER_STARTING.name,
                usdc.micros.toString(),
            )
        }

        is OnrampZecDeliveryStatus.AwaitingZec -> {
            AppleOnrampDeliveryStatus("awaitingZec", OnrampZecDeliveryPhase.AWAITING_ZEC.name, usdc.micros.toString())
        }

        is OnrampZecDeliveryStatus.Delivered -> {
            AppleOnrampDeliveryStatus(
                kind = "delivered",
                stage = OnrampZecDeliveryPhase.DELIVERED.name,
                inputUsdcMicros = inputUsdc.micros.toString(),
                outputZec = outputZec,
                baseTransactionHash = baseTransactionHash,
                fundsLocation = FundsLocation.ZCASH_WALLET.name,
                isTerminal = true,
                isSuccess = true,
            )
        }

        is OnrampZecDeliveryStatus.RefundedToBase -> {
            AppleOnrampDeliveryStatus(
                kind = "refundedToBase",
                stage = OnrampZecDeliveryPhase.REFUNDED_TO_BASE.name,
                inputUsdcMicros = inputUsdc.micros.toString(),
                refundedUsdcMicros = refundedUsdc.micros.toString(),
                baseAccount = baseAccount.checksumHex,
                fundsLocation = FundsLocation.BASE_REFUND_CONFIRMED.name,
                retryable = true,
                isTerminal = true,
            )
        }

        is OnrampZecDeliveryStatus.Failed -> {
            AppleOnrampDeliveryStatus(
                kind = "failed",
                stage = stage.name,
                fundsLocation = fundsLocation.name,
                retryable = retryable,
                isTerminal = true,
            )
        }
    }

private fun OnrampCheckpoint.toApple() =
    AppleOnrampCheckpoint(id, phase.name, orderId, destination.name, zecDelivery?.toApple())

private fun OnrampZecDeliveryCheckpoint.toApple() =
    AppleOnrampDeliveryCheckpoint(
        phase = phase.name,
        usdcMicros = usdcMicros,
        baseAccount = baseAccount,
        zcashRecipient = zcashRecipient,
        depositAddress = depositAddress,
        quoteDeadlineMillis = quoteDeadlineMillis,
        transferStarted = transferStarted,
        userOperationHash = userOperationHash,
        baseTransactionHash = baseTransactionHash,
        outputZec = outputZec,
        refundedUsdcMicros = refundedUsdcMicros,
        acceptedCostBps = acceptedCostBps,
        fundsLocation = fundsLocation.name,
    )

internal val OnrampZecDeliveryCheckpoint.pendingBaseCommitmentMicros: String?
    get() =
        when (fundsLocation) {
            FundsLocation.BASE_ACCOUNT,
            FundsLocation.TRANSFER_AMBIGUOUS,
            -> usdcMicros

            FundsLocation.RECIPIENT_MISMATCH,
            FundsLocation.NEAR_INTENT,
            FundsLocation.ZCASH_WALLET,
            FundsLocation.BASE_REFUND_CONFIRMED,
            -> null
        }

private fun String.toInstruction(payload: String): OnrampPaymentInstruction =
    when (lowercase()) {
        "upi" -> OnrampPaymentInstruction.Upi(address = "", intentUrl = payload, amount = "")
        "qr" -> OnrampPaymentInstruction.Qr(payload)
        "plain" -> OnrampPaymentInstruction.Plain(payload)
        else -> OnrampPaymentInstruction.Fields(emptyList(), qrPayload = payload)
    }
