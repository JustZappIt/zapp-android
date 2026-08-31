// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.orchestrator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.justzappit.evm.abi.AbiEncoder
import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.crypto.Ecies
import xyz.justzappit.evm.math.BigDecimal
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.DecimalRounding
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.math.decimalMultiply
import xyz.justzappit.evm.math.decimalSetScale
import xyz.justzappit.evm.math.minus
import xyz.justzappit.evm.math.plus
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.RpcException
import xyz.justzappit.evm.signer.EcdsaSigner
import xyz.justzappit.evm.signer.TxSubmitter
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.padLeftToWord
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.account.AllowanceTransactionGuard
import xyz.justzappit.offramp.config.P2pNetworkConfig
import xyz.justzappit.offramp.funding.FundingOutcome
import xyz.justzappit.offramp.funding.OfframpFunding
import xyz.justzappit.offramp.funding.OfframpRefund
import xyz.justzappit.offramp.funding.OfframpTopUp
import xyz.justzappit.offramp.funding.RefundResume
import xyz.justzappit.offramp.p2p.CircleId
import xyz.justzappit.offramp.p2p.CircleRouter
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.DiamondCalls
import xyz.justzappit.offramp.p2p.Erc20Calls
import xyz.justzappit.offramp.p2p.InMemoryOrderRecipientUpiCache
import xyz.justzappit.offramp.p2p.InMemoryRelayIdentityStore
import xyz.justzappit.offramp.p2p.OnChainOrderReader
import xyz.justzappit.offramp.p2p.OrderEvents
import xyz.justzappit.offramp.p2p.OrderReadSource
import xyz.justzappit.offramp.p2p.OrderReader
import xyz.justzappit.offramp.p2p.OrderRecipientUpiCache
import xyz.justzappit.offramp.p2p.OrderSnapshot
import xyz.justzappit.offramp.p2p.OrderStatus
import xyz.justzappit.offramp.p2p.OrderType
import xyz.justzappit.offramp.p2p.PlaceOrderArgs
import xyz.justzappit.offramp.p2p.PriceConfigDecoder
import xyz.justzappit.offramp.p2p.RelayIdentity
import xyz.justzappit.offramp.p2p.RelayIdentityStore
import xyz.justzappit.offramp.p2p.SubgraphClient
import xyz.justzappit.offramp.p2p.UpiPayUri
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.p2p.getOrCreate
import xyz.justzappit.offramp.p2p.getPayFeeConfig
import xyz.justzappit.offramp.p2p.getUsdcBalance

interface OfframpDriver {
    fun run(
        request: OfframpRequest,
        paymentDetailsProvider: OfframpPaymentDetailsProvider? = null,
    ): Flow<OfframpStatus>

    fun resume(
        checkpoint: OfframpCheckpoint,
        paymentDetailsProvider: OfframpPaymentDetailsProvider? = null,
    ): Flow<OfframpStatus>

    /**
     * Standalone "top up Base": bridge [addUsdc] of ZEC onto the reusable Base balance, with no order
     * placed. [resumeBridgeHandle] is a persisted 1-Click deposit address — non-null forces the bridge
     * to re-poll the existing deposit instead of opening a second one, so a crash mid-bridge can't
     * double-send the user's ZEC.
     */
    fun bridgeToBase(addUsdc: Usdc6, resumeBridgeHandle: String?): Flow<BridgeToBaseStatus>

    /**
     * Whether any eligible circle would assign a merchant for this order right now.
     *
     * Both amounts matter and both must be the ones the order will actually carry. The Diamond
     * prices eligibility on the pair, not on the USDC alone: PHP circle 16 assigns three merchants
     * for 11 USDC against a zero fiat amount and none at all against the ₱665 that amount really
     * costs. Asking with a placeholder fiat amount therefore returns a near-uniform yes and gates
     * nothing.
     */
    suspend fun merchantAvailability(usdc: Usdc6, fiat: Usdc6, currency: CurrencyCode): MerchantAvailability

    /**
     * "Get my USDC back to ZEC". Cleanup-call selection depends on on-chain order state:
     *  - ACCEPTED / PAID    → `cancelOrder` (user-permitted, refunds escrow) + transfer
     *  - PLACED + expired   → `autoCancelExpiredOrders` (permissionless cleanup) + transfer
     *  - PLACED + active    → transfer only (PAY/SELL escrow nothing at PLACED)
     *  - CANCELLED / null   → transfer only
     */
    fun bridgeFundsBackToZec(orderId: BigInteger?, resume: RefundResume? = null): Flow<OfframpStatus>
}

class OfframpOrchestrator(
    private val rpc: BaseRpcClient,
    private val submitter: TxSubmitter,
    private val allowanceTransactions: AllowanceTransactionGuard = AllowanceTransactionGuard(),
    private val accountAddress: Address,
    private val network: P2pNetworkConfig,
    private val subgraph: SubgraphClient,
    private val orderReader: OrderReadSource,
    private val funding: OfframpFunding,
    private val refund: OfframpRefund,
    // Mainnet-only NEAR bridge for the standalone "top up Base" flow. Defaulted to a throwing stub so
    // tests that only exercise the order path don't have to supply one.
    private val topUp: OfframpTopUp = OfframpTopUp { _, _, _, _ -> error("No top-up configured") },
    private val router: CircleRouter = CircleRouter(),
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    /**
     * After this duration of polling with no terminal transition, the WaitingFor* status emits
     * with `stalled = true` so the UI can hint that the order is taking longer than usual. There
     * is no client-side timeout — the order remains live on-chain until merchant acceptance,
     * completion, the user cancelling, or the executor's order-sweeper auto-cancelling once the
     * Diamond's getOrderExpiry() window (30 min) elapses. Killing flows on a client clock would
     * orphan the user's escrowed USDC.
     */
    private val stalledAfterMs: Long = DEFAULT_STALLED_AFTER_MS,
    // Wall-clock by default; tests inject a monotonic counter — runTest virtual time does not
    // advance the platform wall clock.
    private val clockMs: () -> Long = ::platformCurrentTimeMillis,
    // Authoritative on-chain reader for the merchant pubkey verification — the polling [orderReader]
    // is subgraph-primary and untrusted for the field we encrypt the user's UPI to.
    private val onChainOrderReader: OrderReadSource = OnChainOrderReader(rpc, network),
    // In-memory default for tests; Android injects an encrypted-prefs store.
    private val relayIdentityStore: RelayIdentityStore = InMemoryRelayIdentityStore(),
    // Locally caches each placed order's recipient UPI so the P2P transactions screen can show
    // it later — encUpi on-chain is encrypted to the merchant, so the user cannot recover the
    // VPA from the chain alone. In-memory default for tests; Android injects encrypted prefs.
    private val orderRecipientUpiCache: OrderRecipientUpiCache = InMemoryOrderRecipientUpiCache(),
) : OfframpDriver {
    override fun run(
        request: OfframpRequest,
        paymentDetailsProvider: OfframpPaymentDetailsProvider?,
    ): Flow<OfframpStatus> =
        flow {
            emit(OfframpStatus.Idle)
            driveNewOrder(
                request = request,
                resumeBridgeHandle = null,
                paymentDetailsProvider = paymentDetailsProvider,
            )
        }

    // [resumeBridgeHandle] is a persisted 1-Click deposit address — passing it forces the funding
    // step to re-poll the existing bridge instead of opening a second one, so a crash mid-bridge
    // can't double-send the user's ZEC.
    private suspend fun FlowCollector<OfframpStatus>.driveNewOrder(
        request: OfframpRequest,
        resumeBridgeHandle: String?,
        paymentDetailsProvider: OfframpPaymentDetailsProvider?,
    ) {
        var orderId: BigInteger? = null
        var currentStep = OfframpStep.INITIALIZATION
        var lastTxHash: TxHash? = null
        try {
            // Validate the host-authorized quote before selecting/funding. The fee is read again
            // inside the allowance critical section so a config update cannot race this check.
            requireAuthorizedPayFee(request)
            val relay = relayIdentityStore.getOrCreate()
            val currencyHex = "0x" + AbiEncoder.bytes32String(request.currency.code).value.toHex()

            currentStep = OfframpStep.SELECTING_CIRCLE
            val circles = subgraph.circlesForRouting(currencyHex)
            emit(OfframpStatus.SelectingCircle(candidateCount = circles.size))

            val selectedCircle =
                router.selectCircleForOrder(
                    circles = circles,
                    orderCurrency = currencyHex,
                ) { id -> validateCircleOnChain(id, request.usdcAmount, request.fiatAmount, request.currency) }
            val circleId = selectedCircle.value
            emit(OfframpStatus.SelectingCircle(candidateCount = circles.size, selectedCircleId = circleId))

            // AlreadyFunded short-circuits the bridge — common when a previous cancelled order left
            // USDC refunded into the smart account; emit a distinct status so the UI renders
            // "Using Base balance" instead of "Bridging funds".
            currentStep = OfframpStep.FUNDING
            val outcome =
                funding.ensureFunded(accountAddress, request, resumeHandle = resumeBridgeHandle) { depositAddress ->
                    emit(OfframpStatus.BridgingFunds(amount = request.usdcAmount, depositAddress = depositAddress))
                }
            if (outcome is FundingOutcome.AlreadyFunded) {
                emit(OfframpStatus.FundedFromBase(amount = request.usdcAmount, baseBalance = outcome.currentBalance))
            }

            // Route re-validation: the funding bridge can take minutes, long enough for the merchant the
            // eligibility gate picked to drop out. Re-confirm the circle still has an assignable merchant
            // before committing funds — otherwise placeOrder reverts and the bridged USDC strands.
            check(validateCircleOnChain(selectedCircle, request.usdcAmount, request.fiatAmount, request.currency)) {
                "Selected circle $circleId lost its assignable merchant during funding — not placing the order"
            }

            allowanceTransactions.withApprovalAndSpend {
                currentStep = OfframpStep.APPROVING_USDC
                // Match the official Scan & Pay UI: the fixed PAY fee applies only when the placed
                // amount is at or below the configured small-order threshold.
                val payFee = requireAuthorizedPayFee(request)
                val approveAmount = request.authorizedRequiredBalance ?: (request.usdcAmount + payFee)
                val approveHash =
                    submitter.sendTransaction(
                        to = network.usdcAddress,
                        data = Erc20Calls.approveCalldata(network.diamondAddress, approveAmount),
                    )
                lastTxHash = approveHash
                emit(OfframpStatus.ApprovingUsdc(txHash = approveHash, amount = approveAmount))
                require(submitter.awaitReceipt(approveHash).success) { "USDC approve reverted" }

                currentStep = OfframpStep.PLACING_ORDER
                val placeOrderHash =
                    submitter.sendTransaction(
                        to = network.diamondAddress,
                        data =
                            DiamondCalls.placeOrderCalldata(
                                PlaceOrderArgs(
                                    relayPubKeyEthCrypto = relay.publicKeyHex,
                                    usdcAmount = request.usdcAmount,
                                    recipientAddress = accountAddress,
                                    orderType = OrderType.PAY,
                                    currency = request.currency,
                                    circleId = circleId,
                                    fiatAmountLimit = request.fiatAmountLimit ?: Usdc6.ZERO,
                                ),
                            ),
                    ) { submission ->
                        // Persist the exact signed identity and nonce before the bundler request.
                        // A collector failure aborts this callback, so no placeOrder is sent.
                        try {
                            emit(
                                OfframpStatus.PlacingOrder(
                                    txHash = submission.hash,
                                    circleId = circleId,
                                    amount = request.usdcAmount,
                                    submissionNonceDecimal = submission.nonce.toString(),
                                ),
                            )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            throw PlaceOrderMarkerPersistenceException(error)
                        }
                    }
                lastTxHash = placeOrderHash
                val placeReceipt = submitter.awaitReceipt(placeOrderHash)
                if (!placeReceipt.success) throw PlaceOrderRevertedException()

                orderId =
                    OrderEvents.parseOrderIdFromReceipt(
                        receipt = placeReceipt,
                        diamondAddress = network.diamondAddress,
                        userAddress = accountAddress,
                    ) ?: error("placeOrder receipt did not contain an OrderPlaced log")
            }

            awaitMerchantAndComplete(
                orderId = requireNotNull(orderId),
                request = request,
                knownSetUpiHash = null,
                paymentDetailsProvider = paymentDetailsProvider,
                onStep = { currentStep = it },
                onTxHash = { lastTxHash = it },
            )
        } catch (e: PlaceOrderMarkerPersistenceException) {
            // The marker callback failed before TxSubmitter reached the network. Let the facade
            // retire a host write that may have committed atomically and then thrown.
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(buildFailedStatus(e, orderId, currentStep, lastTxHash))
        }
    }

    // Two branches:
    //  - orderId non-null → resume at merchant-acceptance / completion polling.
    //  - orderId null → fresh start; if a mainnet bridge was already opened,
    //    [bridgeDepositAddress] makes [driveNewOrder] re-poll it instead of re-quoting.
    override fun resume(
        checkpoint: OfframpCheckpoint,
        paymentDetailsProvider: OfframpPaymentDetailsProvider?,
    ): Flow<OfframpStatus> =
        flow {
            emit(OfframpStatus.Idle)
            val fallbackFiat = checkpoint.fiatAmount ?: resolveFallbackFiat(checkpoint)
            val request = checkpoint.toRequest(fallbackFiatAmount = fallbackFiat)
            var orderId = checkpoint.orderIdBig
            var currentStep = checkpoint.currentStep
            var lastTxHash: TxHash? = checkpoint.setUpiTxHash ?: checkpoint.placeOrderTxHash
            try {
                if (orderId == null && checkpoint.placeOrderTxHash != null) {
                    currentStep = OfframpStep.PLACING_ORDER
                    val receipt = submitter.awaitReceipt(checkpoint.placeOrderTxHash)
                    if (!receipt.success) throw PlaceOrderRevertedException()
                    orderId =
                        OrderEvents.parseOrderIdFromReceipt(
                            receipt = receipt,
                            diamondAddress = network.diamondAddress,
                            userAddress = accountAddress,
                        ) ?: error("placeOrder receipt did not contain an OrderPlaced log")
                }
                if (orderId == null) {
                    driveNewOrder(
                        request = request,
                        resumeBridgeHandle = checkpoint.bridgeDepositAddress,
                        paymentDetailsProvider = paymentDetailsProvider,
                    )
                    return@flow
                }
                awaitMerchantAndComplete(
                    orderId = requireNotNull(orderId),
                    request = request,
                    knownSetUpiHash = checkpoint.setUpiTxHash,
                    paymentDetailsProvider = paymentDetailsProvider,
                    onStep = { currentStep = it },
                    onTxHash = { lastTxHash = it },
                )
            } catch (e: PlaceOrderMarkerPersistenceException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(buildFailedStatus(e, orderId, currentStep, lastTxHash))
            }
        }

    override fun bridgeToBase(addUsdc: Usdc6, resumeBridgeHandle: String?): Flow<BridgeToBaseStatus> =
        flow {
            emit(BridgeToBaseStatus.Idle)
            var depositAddress: String? = resumeBridgeHandle
            try {
                topUp.bridge(accountAddress, addUsdc, resumeHandle = resumeBridgeHandle) { addr ->
                    depositAddress = addr
                    emit(BridgeToBaseStatus.Bridging(amount = addUsdc, depositAddress = addr))
                }
                // The bridge has settled (1-Click SUCCESS); the balance read is display-only. Don't let
                // its RPC blip collapse a completed, irreversible bridge into Failed — fall back to the
                // added amount, which the screen's own balance poll corrects on the next refresh.
                val newBalance = runCatching { Usdc6(usdcBalanceOf(accountAddress)) }.getOrDefault(addUsdc)
                emit(BridgeToBaseStatus.Complete(addedAmount = addUsdc, baseBalance = newBalance))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emit(
                    BridgeToBaseStatus.Failed(
                        message = e.message ?: "Bridge failed",
                        depositAddress = depositAddress,
                        cause = e,
                    ),
                )
            }
        }

    // Runs the same selection the order itself runs, with the same amounts, and reports which of the
    // two failure kinds happened. [CircleRouter] already keeps them apart — it lets a read failure
    // propagate and treats only a `false` as "this circle refused" — so the job here is to preserve
    // that distinction rather than flatten it back into a boolean.
    override suspend fun merchantAvailability(
        usdc: Usdc6,
        fiat: Usdc6,
        currency: CurrencyCode,
    ): MerchantAvailability {
        val currencyHex = "0x" + AbiEncoder.bytes32String(currency.code).value.toHex()
        val circles =
            try {
                subgraph.circlesForRouting(currencyHex)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return MerchantAvailability.Undetermined(e)
            }
        return try {
            router.selectCircleForOrder(circles, currencyHex) { id ->
                try {
                    validateCircleOnChain(id, usdc, fiat, currency)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    throw EligibilityReadFailed(e)
                }
            }
            MerchantAvailability.Available
        } catch (e: CancellationException) {
            throw e
        } catch (e: EligibilityReadFailed) {
            MerchantAvailability.Undetermined(e.reason)
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Throwable
        ) {
            // Deliberately the catch-all arm: anything left, once the read failures above are
            // accounted for, is the router reporting that it ran out of candidates — no eligible
            // circle, or every one of them refused this amount. That is an answer about liquidity,
            // not a failure to look, so it is the one case that may legitimately block the button.
            MerchantAvailability.Unavailable
        }
    }

    override fun bridgeFundsBackToZec(orderId: BigInteger?, resume: RefundResume?): Flow<OfframpStatus> =
        flow {
            try {
                if (resume != null) {
                    val target = Address.parse(resume.handle)
                    val transferHash =
                        when {
                            resume.txHash != null -> resume.txHash
                            resume.transferStarted -> null
                            else -> submitRefundTransfer(target, resume.amount)
                        }
                    if (transferHash != null) {
                        require(submitter.awaitReceipt(transferHash).success) { "USDC pull-back transfer reverted" }
                    }
                    refund.awaitSettlement(resume.handle)
                    emit(
                        OfframpStatus.FundsRecovered(
                            amount = resume.amount,
                            target = target,
                        ),
                    )
                    return@flow
                }
                cleanUpOrderIfNeeded(orderId)
                val balance = Usdc6(usdcBalanceOf(accountAddress))
                if (balance <= Usdc6.ZERO) {
                    emit(OfframpStatus.FundsRecovered(amount = Usdc6.ZERO))
                    return@flow
                }
                val target = refund.pullbackTarget(accountAddress, balance)
                if (target == null) {
                    // No NEAR route (testnet): USDC is already in the self-custodial account.
                    emit(OfframpStatus.FundsRecovered(amount = balance))
                    return@flow
                }
                val transferHash = submitRefundTransfer(target, balance)
                require(submitter.awaitReceipt(transferHash).success) { "USDC pull-back transfer reverted" }
                refund.awaitSettlement(target.checksumHex)
                emit(OfframpStatus.FundsRecovered(amount = balance, target = target, txHash = transferHash))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emit(buildFailedStatus(e, orderId, OfframpStep.WAITING_FOR_ACCEPTANCE, null))
            }
        }

    private suspend fun submitRefundTransfer(target: Address, amount: Usdc6): xyz.justzappit.evm.types.TxHash {
        refund.markTransferStarting(target.checksumHex, amount)
        val transferHash =
            submitter.sendTransaction(
                to = network.usdcAddress,
                data = Erc20Calls.transferCalldata(target, amount),
            )
        refund.markTransferSubmitted(target.checksumHex, amount, transferHash)
        return transferHash
    }

    private suspend fun cleanUpOrderIfNeeded(orderId: BigInteger?) {
        if (orderId == null) return
        val status = runCatching { orderReader.fetchOrder(orderId)?.status }.getOrNull() ?: return
        when (status) {
            OrderStatus.ACCEPTED, OrderStatus.PAID -> {
                val hash =
                    submitter.sendTransaction(
                        to = network.diamondAddress,
                        data = DiamondCalls.cancelOrderCalldata(orderId),
                    )
                require(submitter.awaitReceipt(hash).success) { "cancelOrder reverted" }
            }

            OrderStatus.PLACED -> {
                if (checkOrderExpired(orderId)) {
                    val hash =
                        submitter.sendTransaction(
                            to = network.diamondAddress,
                            data = DiamondCalls.autoCancelExpiredOrdersCalldata(listOf(orderId)),
                        )
                    require(submitter.awaitReceipt(hash).success) { "autoCancelExpiredOrders reverted" }
                }
            }

            OrderStatus.COMPLETED, OrderStatus.CANCELLED -> {
                Unit
            }
        }
    }

    // Subgraph can lag the chain, so re-read on-chain when it claims "no UPI yet". The on-chain
    // read fails closed: errors and a null result both propagate, since silently treating either
    // as "no UPI" would re-broadcast setSellOrderUpi and revert UpiAlreadySent.
    private suspend fun isUpiAlreadyOnChain(orderId: BigInteger, accepted: OrderSnapshot): Boolean {
        if (accepted.status.onChain >= OrderStatus.PAID.onChain) return true
        if (accepted.encryptedUserUpi.isNotBlank()) return true
        if (accepted.source == OrderSnapshot.Source.OnChain) return false
        val onChain =
            onChainOrderReader.fetchOrder(orderId)
                ?: error("Cannot verify UPI idempotency for order $orderId — on-chain reader returned no order")
        return onChain.encryptedUserUpi.isNotBlank() ||
            onChain.status.onChain >= OrderStatus.PAID.onChain
    }

    // Encrypt UPI only to the on-chain pubkey: a compromised indexer could swap in an attacker key
    // and harvest the plaintext. Fail closed if the on-chain read disagrees with what subgraph gave us;
    // if subgraph omitted the field, trust the on-chain value (it's the source of truth anyway).
    private suspend fun verifiedMerchantPubKey(orderId: BigInteger, accepted: OrderSnapshot): String {
        if (accepted.source == OrderSnapshot.Source.OnChain) return accepted.merchantPubKey
        val onChain =
            onChainOrderReader.fetchOrder(orderId)
                ?: error("Cannot verify merchant pubkey on-chain for order $orderId — refusing to encrypt UPI")
        check(onChain.merchantPubKey.isNotBlank()) {
            "On-chain merchant pubkey is empty for order $orderId — refusing to encrypt UPI"
        }
        if (accepted.merchantPubKey.isNotBlank()) {
            check(onChain.merchantPubKey.equals(accepted.merchantPubKey, ignoreCase = true)) {
                "Merchant pubkey disagrees between subgraph and chain for order $orderId — refusing to encrypt UPI"
            }
        }
        return onChain.merchantPubKey
    }

    private suspend fun usdcBalanceOf(account: Address): BigInteger =
        rpc.getUsdcBalance(network.usdcAddress, account).micros

    /**
     * Encrypts the scanned QR payload, mirroring the web client: parse/validate locally, but submit
     * the original scanned string to setSellOrderUpi. If the QR has its own amount, pass the parsed
     * amount as updatedAmount; otherwise keep the placed amount.
     */
    private suspend fun broadcastSetSellOrderUpi(
        orderId: BigInteger,
        accepted: OrderSnapshot,
        request: OfframpRequest,
        paymentDetails: OfframpPaymentDetails,
        onStep: (OfframpStep) -> Unit,
    ): TxHash {
        val merchantPubKey = verifiedMerchantPubKey(orderId, accepted)
        val scannedFiatAmount = paymentDetails.fiatAmount
        val paymentPayload = paymentDetails.rawPayload

        val parsedUsdcMicros =
            if (scannedFiatAmount != null) {
                val sellPrice =
                    runCatching { readSellPriceInrPerUsdc(request.currency) }
                        .getOrElse { e ->
                            error(
                                "Unable to read sell price for scanned QR amount: " +
                                    (e.message ?: e::class.simpleName),
                            )
                        }
                require(sellPrice.signum() > 0) { "Sell price must be positive for scanned QR amount" }
                bigIntegerValueOf(UpiPayUri.parsedUsdcMicros(scannedFiatAmount, sellPrice))
            } else {
                request.usdcAmount.micros
            }
        val placedMicros = request.usdcAmount.micros
        val updatedAmount = parsedUsdcMicros

        validateQrAmountAdjustment(placedMicros, updatedAmount)

        return allowanceTransactions.withApprovalAndSpend {
            // placeOrder already pulled `placed`. setSellOrderUpi pulls only a positive QR
            // adjustment plus the fixed PAY fee. Always write that exact immediate allowance under
            // the shared guard: another rail may have overwritten the leftover allowance while we
            // waited for merchant acceptance, even when the scanned amount did not increase.
            val payFee = requireAuthorizedPayFee(request)
            val adjustment = if (updatedAmount > placedMicros) updatedAmount - placedMicros else BigInteger("0")
            val immediateDebit = Usdc6(adjustment + payFee.micros)
            request.authorizedRequiredBalance?.let { authorized ->
                require(request.usdcAmount + immediateDebit <= authorized) {
                    "The scanned amount or PAY fee exceeds the quoted Base debit; request a new quote"
                }
            }
            if (immediateDebit > Usdc6.ZERO) {
                val availableBalance = Usdc6(usdcBalanceOf(accountAddress))
                require(availableBalance >= immediateDebit) {
                    "Base balance is insufficient for the scanned QR adjustment and payment fee"
                }
                val approveHash =
                    submitter.sendTransaction(
                        to = network.usdcAddress,
                        data = Erc20Calls.approveCalldata(network.diamondAddress, immediateDebit),
                    )
                require(submitter.awaitReceipt(approveHash).success) {
                    "USDC allowance for setSellOrderUpi reverted"
                }
            }

            orderRecipientUpiCache.put(orderId.toString(), paymentDetails.paymentAddress)

            val relay = relayIdentityStore.getOrCreate()
            val cipherHex = encryptUpiEnvelopeForMerchant(relay, paymentPayload, merchantPubKey)
            onStep(OfframpStep.SENDING_UPI)
            val setUpiHash =
                submitter.sendTransaction(
                    to = network.diamondAddress,
                    data =
                        DiamondCalls.setSellOrderUpiCalldata(
                            orderId = orderId,
                            encryptedUpiHex = cipherHex,
                            updatedAmount = updatedAmount,
                        ),
                )
            require(submitter.awaitReceipt(setUpiHash).success) { "setSellOrderUpi reverted" }
            setUpiHash
        }
    }

    private suspend fun resolvePaymentDetails(
        orderId: BigInteger,
        accepted: OrderSnapshot,
        request: OfframpRequest,
        paymentDetailsProvider: OfframpPaymentDetailsProvider?,
    ): OfframpPaymentDetails =
        paymentDetailsProvider?.requestPaymentDetails(orderId, accepted, request)
            ?: legacyPaymentDetails(request)

    private suspend fun readSellPriceInrPerUsdc(currency: CurrencyCode): BigDecimal {
        val ret =
            rpc.ethCall(
                to = network.diamondAddress,
                data = DiamondCalls.getPriceConfigCalldata(currency),
            )
        return PriceConfigDecoder.decode(ret).sellPriceAsRate()
    }

    private suspend fun readPayFixedFeeFor(amount: Usdc6, currency: CurrencyCode): Usdc6 =
        rpc.getPayFeeConfig(network.diamondAddress, currency).feeFor(amount)

    /**
     * Returns the live fee after proving it still matches the caller's accepted quote. This check
     * runs immediately before each allowance/spend pair as well as before funding, because the
     * fixed fee is charged later by setSellOrderUpi rather than snapshotted by placeOrder.
     */
    private suspend fun requireAuthorizedPayFee(request: OfframpRequest): Usdc6 {
        val live = readPayFixedFeeFor(request.usdcAmount, request.currency)
        val authorized = request.authorizedPayFee ?: return live
        require(live == authorized) {
            "The PAY fee changed since this quote; request a new quote before continuing"
        }
        require(request.authorizedRequiredBalance == request.usdcAmount + authorized) {
            "The authorized Base debit no longer matches the order amount and PAY fee"
        }
        return authorized
    }

    private suspend fun resolveFallbackFiat(checkpoint: OfframpCheckpoint): Usdc6 {
        val rate = runCatching { readSellPriceInrPerUsdc(checkpoint.currency) }.getOrNull()
        return if (rate != null && rate.signum() > 0) {
            Usdc6.ofWhole(decimalMultiply(checkpoint.usdcAmount.whole, rate))
        } else {
            checkpoint.usdcAmount
        }
    }

    private suspend fun FlowCollector<OfframpStatus>.awaitMerchantAndComplete(
        orderId: BigInteger,
        request: OfframpRequest,
        knownSetUpiHash: TxHash?,
        paymentDetailsProvider: OfframpPaymentDetailsProvider?,
        onStep: (OfframpStep) -> Unit,
        onTxHash: (TxHash) -> Unit,
    ) {
        onStep(OfframpStep.WAITING_FOR_ACCEPTANCE)
        val accepted =
            when (val r = pollForAcceptance(orderId)) {
                is PollOutcome.Cancelled -> {
                    emitCancelled(orderId, r.snapshot)
                    return
                }

                is PollOutcome.Matched -> {
                    r.snapshot
                }
            }
        val acceptedMerchant =
            requireNotNull(accepted.acceptedMerchantAddress) {
                "Order $orderId reached ACCEPTED but acceptedMerchantAddress is null"
            }

        // Resume safety: if the encrypted UPI is already on-chain — the setSellOrderUpi tx landed
        // before its hash was checkpointed, or the order already advanced past ACCEPTED — re-sending
        // it reverts with UpiAlreadySent. Broadcast only when we have not already done so.
        var sentPaymentAddress = request.recipientUpi
        val setUpiHash: TxHash? =
            when {
                knownSetUpiHash != null -> {
                    knownSetUpiHash
                }

                isUpiAlreadyOnChain(orderId, accepted) -> {
                    null
                }

                else -> {
                    onStep(OfframpStep.WAITING_FOR_PAYMENT_DETAILS)
                    emit(
                        OfframpStatus.WaitingForPaymentDetails(
                            orderId = orderId,
                            merchantAddress = acceptedMerchant,
                            merchantPubKey = accepted.merchantPubKey,
                            acceptedAtEpochSeconds = accepted.acceptedAtEpochSeconds,
                        ),
                    )
                    val paymentDetails = resolvePaymentDetails(orderId, accepted, request, paymentDetailsProvider)
                    sentPaymentAddress = paymentDetails.paymentAddress
                    onStep(OfframpStep.ENCRYPTING_UPI)
                    broadcastSetSellOrderUpi(orderId, accepted, request, paymentDetails, onStep)
                }
            }
        if (setUpiHash != null) {
            onTxHash(setUpiHash)
            onStep(OfframpStep.SENDING_UPI)
            emit(
                OfframpStatus.SendingEncryptedUpi(
                    orderId = orderId,
                    txHash = setUpiHash,
                    merchantAddress = acceptedMerchant,
                    merchantPubKey = accepted.merchantPubKey,
                    paymentAddress = sentPaymentAddress,
                    acceptedAtEpochSeconds = accepted.acceptedAtEpochSeconds,
                ),
            )
        }

        onStep(OfframpStep.WAITING_FOR_COMPLETION)
        val finished =
            when (val r = pollForCompletion(orderId, accepted)) {
                is PollOutcome.Cancelled -> {
                    emitCancelled(orderId, r.snapshot, fallbackAccepted = accepted)
                    return
                }

                is PollOutcome.Matched -> {
                    r.snapshot
                }
            }
        emit(
            OfframpStatus.Completed(
                orderId = orderId,
                acceptedMerchant = finished.acceptedMerchantAddress ?: acceptedMerchant,
                placedAtEpochSeconds = finished.placedAtEpochSeconds ?: accepted.placedAtEpochSeconds,
                acceptedAtEpochSeconds = finished.acceptedAtEpochSeconds ?: accepted.acceptedAtEpochSeconds,
                paidAtEpochSeconds = finished.paidAtEpochSeconds,
                completedAtEpochSeconds = finished.completedAtEpochSeconds,
            ),
        )
    }

    private suspend fun FlowCollector<OfframpStatus>.emitCancelled(
        orderId: BigInteger,
        snapshot: OrderSnapshot,
        fallbackAccepted: OrderSnapshot? = null,
    ) {
        emit(
            OfframpStatus.Cancelled(
                orderId = orderId,
                cancelledAtEpochSeconds = snapshot.cancelledAtEpochSeconds,
                // On cancellation the contract refunds the placed USDC; subgraph's actualUsdcAmount
                // is only populated on COMPLETED, so fall back to the originally-placed amount.
                refundedUsdcAmount = snapshot.actualUsdcAmount ?: snapshot.usdcAmount,
                acceptedMerchant =
                    snapshot.acceptedMerchantAddress
                        ?: fallbackAccepted?.acceptedMerchantAddress,
            ),
        )
    }

    // Only an empty assignable array means "no merchants right now"; RPC failures propagate so
    // they surface as Failed rather than burning through MAX_VALIDATION_ATTEMPTS as bad circles.
    private suspend fun validateCircleOnChain(
        circleId: CircleId,
        usdcAmount: Usdc6,
        fiatAmount: Usdc6,
        currency: CurrencyCode,
    ): Boolean {
        val ret =
            rpc.ethCall(
                to = network.diamondAddress,
                data =
                    DiamondCalls.getAssignableMerchantsFromCircleCalldata(
                        circleId = circleId.value,
                        assignUpTo = bigIntegerValueOf(ASSIGN_UP_TO),
                        currency = currency,
                        user = accountAddress,
                        usdtAmount = usdcAmount,
                        fiatAmount = fiatAmount,
                        orderType = OrderType.PAY,
                    ),
            )
        return OrderReader.decodeAddressArrayNonEmpty(ret)
    }

    private suspend fun FlowCollector<OfframpStatus>.pollForAcceptance(orderId: BigInteger): PollOutcome =
        pollOrderUntil(
            orderId = orderId,
            buildStatus = { attempt, lastSeen, stalled, expired ->
                OfframpStatus.WaitingForMerchantAcceptance(
                    orderId = orderId,
                    pollAttempts = attempt,
                    lastObservedStatus = lastSeen,
                    stalled = stalled,
                    expired = expired,
                )
            },
            predicate = { it.isAccepted },
        )

    private suspend fun FlowCollector<OfframpStatus>.pollForCompletion(
        orderId: BigInteger,
        accepted: OrderSnapshot,
    ): PollOutcome =
        pollOrderUntil(
            orderId = orderId,
            buildStatus = { attempt, lastSeen, stalled, expired ->
                OfframpStatus.WaitingForCompletion(
                    orderId = orderId,
                    pollAttempts = attempt,
                    lastObservedStatus = lastSeen,
                    stalled = stalled,
                    expired = expired,
                    acceptedAtEpochSeconds = accepted.acceptedAtEpochSeconds,
                    paidAtEpochSeconds = null,
                )
            },
            predicate = { it.status == OrderStatus.COMPLETED },
        )

    private suspend fun checkOrderExpired(orderId: BigInteger): Boolean =
        runCatching {
            val ret = rpc.ethCall(to = network.diamondAddress, data = DiamondCalls.isOrderExpiredCalldata(orderId))
            ret.isNotEmpty() && BigInteger(1, ret).signum() != 0
        }.getOrDefault(false)

    // No client-side deadline (see [stalledAfterMs] for the UX-only "taking a while" signal).
    // CANCELLED is a normal terminal — the contract has refunded the user's USDC on-chain — and
    // returned as a [PollOutcome.Cancelled], not thrown. Transient RPC failures are swallowed so
    // a single bad poll can't kill an order whose USDC is already escrowed.
    private suspend fun FlowCollector<OfframpStatus>.pollOrderUntil(
        orderId: BigInteger,
        buildStatus: (attempt: Int, lastSeen: OrderStatus?, stalled: Boolean, expired: Boolean) -> OfframpStatus,
        predicate: (OrderSnapshot) -> Boolean,
    ): PollOutcome {
        var attempt = 0
        val startedAtMs = clockMs()
        emit(buildStatus(attempt, null, false, false))
        while (true) {
            attempt++
            val stalled = clockMs() - startedAtMs >= stalledAfterMs
            val snapshot =
                try {
                    orderReader.fetchOrder(orderId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // FallbackOrderReader already logs both legs; orchestrator just keeps polling.
                    null
                }
            if (snapshot != null) {
                if (snapshot.status == OrderStatus.CANCELLED) {
                    return PollOutcome.Cancelled(snapshot)
                }
                if (predicate(snapshot)) return PollOutcome.Matched(snapshot)
                val expired = checkOrderExpired(orderId)
                emit(buildStatus(attempt, snapshot.status, stalled, expired))
            } else {
                emit(buildStatus(attempt, null, stalled, false))
            }
            delay(pollIntervalMs)
        }
    }
}

private const val ASSIGN_UP_TO = 3L

/**
 * Marks a throwable as having come from the eligibility read rather than from the router's own
 * "nobody left to try". Tagging it at the throw site is what lets [MerchantAvailability] tell a
 * dead corridor from an unreachable one without matching on exception types or message strings.
 */
private class EligibilityReadFailed(
    val reason: Throwable
) : Exception(reason)

private const val DEFAULT_POLL_INTERVAL_MS = 3_000L
private const val DEFAULT_STALLED_AFTER_MS = 5L * 60 * 1000

// viem `serializeSignature` v offset — adds 27 to recId so v ∈ {0x1b, 0x1c}.
private const val SIG_V_OFFSET = 27

private sealed class PollOutcome {
    data class Matched(
        val snapshot: OrderSnapshot
    ) : PollOutcome()

    data class Cancelled(
        val snapshot: OrderSnapshot
    ) : PollOutcome()
}

/**
 * Wrap the UPI URI in the SDK's signed `{message, signature}` JSON envelope before ECIES.
 * Mirrors `@p2pdotme/sdk` `crypto/encryption.ts:encryptPaymentAddress`. Without the envelope
 * the merchant's parser sees a raw URI, throws on `JSON.parse`, and the strict merchant pool
 * (e.g. `0x70e45df…`) atomic-cancels inside our own setSellOrderUpi. Verified mainnet 2026-05-24:
 * 290-char raw-URI encUpi from this orchestrator vs 610-char SDK-wrapped encUpi from the
 * Node test rig; strict merchant accepts only the wrapped form.
 *
 * Signature is ECDSA over `keccak256(utf8(uri))` with the relay identity's private key, encoded
 * as viem's `serializeSignature`: `r(32) | s(32) | v(1)` where v ∈ {0x1b, 0x1c}.
 */
private fun encryptUpiEnvelopeForMerchant(relay: RelayIdentity, qrUri: String, merchantPubKey: String): String {
    val privateKey = BigInteger(1, relay.privateKeyHex.removePrefix("0x").hexToBytes())
    val messageHash = keccak256(qrUri.encodeToByteArray())
    val sig = EcdsaSigner.sign(messageHash, privateKey)
    val sigBytes =
        sig.r.toByteArray().padLeftToWord() +
            sig.s.toByteArray().padLeftToWord() +
            byteArrayOf((sig.yParity + SIG_V_OFFSET).toByte())
    val sigHex = "0x" + sigBytes.toHex()
    val payload =
        Json.encodeToString(
            kotlinx.serialization.json.JsonObject
                .serializer(),
            buildJsonObject {
                put("message", qrUri)
                put("signature", sigHex)
            },
        )
    return Ecies.cipherStringify(Ecies.encryptWithPublicKey(merchantPubKey, payload))
}

private fun legacyPaymentDetails(request: OfframpRequest): OfframpPaymentDetails {
    require(request.recipientUpi.isNotBlank()) {
        "recipientUpi must not be blank when no paymentDetailsProvider is supplied"
    }
    val inrAmount =
        decimalSetScale(
            request.fiatAmount.whole,
            UpiPayUri.INR_DECIMAL_PLACES,
            DecimalRounding.DOWN,
        )
    val payload =
        UpiPayUri.build(
            vpa = request.recipientUpi,
            payeeName = request.payeeName,
            inrAmount = inrAmount,
            currencyCode = request.currency.code,
        )
    return OfframpPaymentDetails(
        rawPayload = payload,
        paymentAddress = request.recipientUpi,
        fiatAmount = inrAmount,
    )
}

private fun buildFailedStatus(
    error: Throwable,
    orderId: BigInteger?,
    step: OfframpStep,
    lastTxHash: TxHash?,
): OfframpStatus.Failed =
    when (error) {
        is RpcException.ExecutionReverted -> {
            val lookup = KnownReverts.lookup(error.selector)
            OfframpStatus.Failed(
                message = error.message ?: "execution reverted",
                orderId = orderId,
                step = step,
                txHash = lastTxHash,
                revertSelector = error.selector,
                knownRevertReason = lookup.reason,
                sdkErrorName = lookup.sdkName,
                sdkErrorMessage = lookup.sdkMessage,
                solidityErrorString = error.solidityErrorString,
                nothingEscrowed = step == OfframpStep.PLACING_ORDER && error.provesPlaceOrderNotEscrowed,
                cause = error,
            )
        }

        // ERC-4337 reverts surface as an opaque bundler error message ("...reverted during
        // simulation with reason: 0xea8e4eb5"), not a structured ExecutionReverted. Recover the
        // selector from the message so AA-path reverts map to the same curated/SDK reasons.
        is RpcException.Unknown -> {
            val selector = KnownReverts.selectorFromMessage(error.errorMessage ?: error.raw)
            val lookup = KnownReverts.lookup(selector)
            OfframpStatus.Failed(
                message = error.errorMessage ?: error.message ?: "Unknown error",
                orderId = orderId,
                step = step,
                txHash = lastTxHash,
                revertSelector = selector,
                knownRevertReason = lookup.reason,
                sdkErrorName = lookup.sdkName,
                sdkErrorMessage = lookup.sdkMessage,
                nothingEscrowed = step == OfframpStep.PLACING_ORDER && error.provesPlaceOrderNotEscrowed,
                cause = error,
            )
        }

        else -> {
            OfframpStatus.Failed(
                message = error.message ?: error::class.simpleName ?: "Unknown error",
                orderId = orderId,
                step = step,
                txHash = lastTxHash,
                nothingEscrowed = step == OfframpStep.PLACING_ORDER && error.provesPlaceOrderNotEscrowed,
                cause = error,
            )
        }
    }

private class PlaceOrderRevertedException : Exception("placeOrder reverted")

internal class PlaceOrderMarkerPersistenceException(
    cause: Exception,
) : Exception("placeOrder recovery marker could not be persisted", cause)

private val Throwable.provesPlaceOrderNotEscrowed: Boolean
    get() =
        this is PlaceOrderRevertedException ||
            (
                this is RpcException &&
                    method in setOf("eth_sendUserOperation", "eth_sendRawTransaction") &&
                    this !is RpcException.TransportError
            )
