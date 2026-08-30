// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import xyz.justzappit.evm.abi.AbiDecoder
import xyz.justzappit.evm.abi.AbiEncoder
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.TransactionReceipt
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.account.Erc4337SubmitterProvider
import xyz.justzappit.offramp.account.OfframpAccountProvider
import xyz.justzappit.offramp.account.SubmittingAccount
import xyz.justzappit.offramp.config.P2pNetworkConfig
import xyz.justzappit.offramp.p2p.CircleRouter
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.DiamondCalls
import xyz.justzappit.offramp.p2p.OrderEvents
import xyz.justzappit.offramp.p2p.OrderFeeDetails
import xyz.justzappit.offramp.p2p.OrderReadSource
import xyz.justzappit.offramp.p2p.OrderReader
import xyz.justzappit.offramp.p2p.OrderRecipientUpiCache
import xyz.justzappit.offramp.p2p.OrderSnapshot
import xyz.justzappit.offramp.p2p.OrderStatus
import xyz.justzappit.offramp.p2p.OrderType
import xyz.justzappit.offramp.p2p.PaymentAddressDecryptor
import xyz.justzappit.offramp.p2p.PlaceOrderArgs
import xyz.justzappit.offramp.p2p.PriceConfig
import xyz.justzappit.offramp.p2p.PriceConfigDecoder
import xyz.justzappit.offramp.p2p.RelayIdentityStore
import xyz.justzappit.offramp.p2p.SubgraphClient
import xyz.justzappit.offramp.p2p.UpiPayUri
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.p2p.getAdditionalOrderDetails
import xyz.justzappit.offramp.p2p.getOrCreate
import xyz.justzappit.offramp.reputation.ReputationCalls

/**
 * Places a BUY from the user's own ERC-4337 smart account, with no operator service anywhere on
 * the path.
 *
 * This is the same [OnrampDriver] contract the custodial route implements, so the screens, the
 * checkpoint and the ZEC delivery coordinator are untouched and the two routes swap from DI. What
 * changes is who carries the responsibilities the service used to:
 *
 * - **The order is placed by the user**, so the Diamond gates it on *their* reputation. A cold
 *   wallet cannot place a BUY of any size, which is why the amount screen is never reached at 0 RP.
 * - **The device-screening record is filed by the app and signed by the user.** On the operator
 *   route the service filed it, signed as itself, which is what made it match the address the chain
 *   showed as the placer. Without a matching record an order routes and prices normally and is
 *   then never accepted — about 1 in 20 fill. That is why an unconfigured screening service
 *   disables this route outright rather than quietly placing orders that cannot fill.
 * - **The merchant's payment handle is decrypted on the device**, with a relay key only this
 *   wallet holds. Losing that key leaves an order that cannot be paid; the custodial route had no
 *   such failure mode because the service held the key.
 */
@Suppress("TooManyFunctions")
class DirectOnrampDriver(
    private val rpc: BaseRpcClient,
    private val network: P2pNetworkConfig,
    private val submitters: Erc4337SubmitterProvider,
    private val accountProvider: OfframpAccountProvider,
    private val subgraph: SubgraphClient,
    /**
     * Reads go to the chain rather than the indexer: the subgraph returns `encUpi` empty, and that
     * field is the whole payment step.
     */
    private val orderReader: OrderReadSource,
    private val screening: OnrampScreeningClient?,
    private val relayIdentityStore: RelayIdentityStore,
    private val orderRecipientUpiCache: OrderRecipientUpiCache,
    private val router: CircleRouter = CircleRouter(),
    private val country: String? = null,
    private val nowMillis: () -> Long = { 0L },
    private val acceptPollMillis: Long = ACCEPT_POLL_MILLIS,
    private val settlePollMillis: Long = SETTLE_POLL_MILLIS,
    private val acceptPollAttempts: Int = ACCEPT_POLL_ATTEMPTS,
    private val settlePollAttempts: Int = SETTLE_POLL_ATTEMPTS,
) : OnrampDriver {
    override suspend fun limits(currency: CurrencyCode): OnrampLimits {
        // No screening service means orders that place and then never fill. Closing the corridor
        // is the honest failure; placing anyway is the expensive one.
        if (screening == null) return OnrampLimits.DISABLED.copy(currency = currency)
        return try {
            val account = submitters.resolve()
            coroutineScope {
                val price = async { readPriceConfig(currency) }
                val fee = async { readUsdc(DiamondCalls.getSmallOrderFixedFeeBuyCalldata(currency)) }
                val buyLimit = async { readBuyLimit(account.address, currency) }
                val open = async { isCorridorOpen(currency) }
                DirectOnrampPricing.limitsFor(
                    buyLimit = buyLimit.await(),
                    price = price.await(),
                    fixedFeeBuy = fee.await(),
                    enabled = open.await(),
                    currency = currency,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            OnrampLimits.DISABLED.copy(currency = currency)
        }
    }

    /**
     * Asked of the chain rather than of a service: a corridor opening or closing takes effect
     * without an app release, and an unreachable chain answers "no idea" — an empty set — so the
     * caller falls back to its default rather than trusting a stale list.
     */
    override suspend fun buyCorridors(): Set<CurrencyCode> =
        try {
            if (!readExchangeStatus()) {
                emptySet()
            } else {
                coroutineScope {
                    CurrencyCode.entries
                        .map { currency -> async { currency.takeIf { readCurrencySupported(it) } } }
                        .awaitAll()
                        .filterNotNull()
                        .toSet()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            emptySet()
        }

    override suspend fun recipientAddress(): Address = submitters.resolve().address

    /**
     * Priced from the Diamond's own buy rate, not from a service. The lock is local and short: a
     * price tick between quoting and placing reverts `SlippageExceeded`, and re-quoting is the
     * only handling for that, so an expired quote is refused before it costs gas.
     */
    override suspend fun quote(fiatAmount: Usdc6, currency: CurrencyCode): OnrampQuote =
        coroutineScope {
            val price = async { readPriceConfig(currency) }
            val threshold = async { readUsdc(DiamondCalls.getSmallOrderThresholdCalldata(currency)) }
            val fee = async { readUsdc(DiamondCalls.getSmallOrderFixedFeeBuyCalldata(currency)) }
            val quote =
                DirectOnrampPricing.quote(
                    fiatAmount = fiatAmount,
                    price = price.await(),
                    threshold = threshold.await(),
                    fixedFeeBuy = fee.await(),
                )
            OnrampQuote(
                quoteId = QUOTE_ID_PREFIX + nowMillis().toString(),
                currency = currency,
                fiatAmount = quote.fiatAmount,
                grossUsdc = quote.grossUsdc,
                feeUsdc = quote.feeUsdc,
                netUsdc = quote.netUsdc,
                buyPrice = quote.buyPrice,
                expiresAtMillis = nowMillis() + QUOTE_TTL_MILLIS,
            )
        }

    override fun start(quote: OnrampQuote): Flow<OnrampStatus> =
        flow {
            emit(OnrampStatus.Placing(id = null))
            val account = submitters.resolve()
            val orderId = place(quote, account) ?: return@flow
            watch(orderId, account, fromPaid = false)
        }.guarded(OnrampPhase.PLACING, id = null, orderId = null)

    /**
     * ☠ Never called by any schedule, retry or resume path — only from an explicit user
     * confirmation. This is a *claim* that fiat moved, not proof of it, and a false claim costs
     * the user 5 RP, which at 1 RP = $1 is $5 off their buy limit.
     */
    override fun confirmPaid(checkpoint: OnrampCheckpoint): Flow<OnrampStatus> =
        flow {
            val orderId =
                checkpoint.orderId?.toBigIntegerOrNull() ?: run {
                    emit(failed(OnrampFailureCode.ORDER_NOT_FOUND, OnrampPhase.CONFIRMING_PAID, checkpoint))
                    return@flow
                }
            emit(OnrampStatus.ConfirmingPaid(checkpoint.id, checkpoint.orderId))
            val account = submitters.resolve()
            val hash =
                account.submitter.sendTransaction(
                    to = network.diamondAddress,
                    data = DiamondCalls.paidBuyOrderCalldata(orderId),
                )
            require(account.submitter.awaitReceipt(hash).success) { "paidBuyOrder reverted" }
            watch(orderId, account, fromPaid = true, paidTx = hash.hex)
        }.guarded(OnrampPhase.CONFIRMING_PAID, checkpoint.id, checkpoint.orderId)

    /**
     * Branches on what the chain says now, never on the stored stage: a process killed between
     * sending and recording leaves a live order the app has no id for, so the tx hash is re-read
     * and the receipt re-parsed before anything else is decided.
     */
    override fun resume(checkpoint: OnrampCheckpoint): Flow<OnrampStatus> =
        flow {
            val account = submitters.resolve()
            val orderId = checkpoint.orderId?.toBigIntegerOrNull() ?: recoverOrderId(checkpoint, account)
            if (orderId == null) {
                emit(failed(OnrampFailureCode.ORDER_NOT_FOUND, checkpoint.phase, checkpoint))
                return@flow
            }
            watch(orderId, account, fromPaid = false)
        }.guarded(checkpoint.phase, checkpoint.id, checkpoint.orderId)

    override fun cancel(checkpoint: OnrampCheckpoint): Flow<OnrampStatus> =
        flow {
            val orderId = checkpoint.orderId?.toBigIntegerOrNull()
            if (orderId != null) {
                val account = submitters.resolve()
                val hash =
                    account.submitter.sendTransaction(
                        to = network.diamondAddress,
                        data = DiamondCalls.cancelOrderCalldata(orderId),
                    )
                // A merchant who accepts between the last poll and this cancellation being included
                // makes cancelOrder revert, and the order is then very much alive. Saying
                // "cancelled" there drops the checkpoint and forgets an order the user is about to
                // be asked to pay, so the chain's own answer is what the screen goes back to.
                if (!account.submitter.awaitReceipt(hash).success) {
                    watch(orderId, account, fromPaid = false)
                    return@flow
                }
            }
            emit(OnrampStatus.Cancelled(checkpoint.id, checkpoint.orderId))
        }.guarded(checkpoint.phase, checkpoint.id, checkpoint.orderId)

    // ---- placement ----

    /** Screen, route, place. Returns null having already emitted the failure. */
    @Suppress("ReturnCount")
    private suspend fun FlowCollector<OnrampStatus>.place(
        quote: OnrampQuote,
        account: SubmittingAccount,
    ): BigInteger? {
        if (nowMillis() > quote.expiresAtMillis) {
            emit(OnrampStatus.Failed(OnrampFailureCode.QUOTE_EXPIRED, OnrampPhase.PLACING, null, null))
            return null
        }
        // A moved buy price would revert SlippageExceeded on chain; catching it here costs a read
        // rather than a failed UserOp the user waits on.
        if (readPriceConfig(quote.currency).buyPrice != quote.buyPrice) {
            emit(OnrampStatus.Failed(OnrampFailureCode.QUOTE_EXPIRED, OnrampPhase.PLACING, null, null))
            return null
        }

        val fiatAmountLimit = DirectOnrampPricing.fiatAmountLimit(quote.netUsdc, quote.buyPrice)
        val circleId = selectCircle(quote, account.address, fiatAmountLimit)
        val screened = screen(quote, account, fiatAmountLimit)
        if (screened is ScreeningResult.Rejected) {
            emit(OnrampStatus.Failed(OnrampFailureCode.SCREENING_REJECTED, OnrampPhase.PLACING, null, null))
            return null
        }

        // Merchants drop out of a circle while all of the above happens, and a stale circle makes
        // placeOrder revert.
        check(validateCircle(circleId, quote, account.address, fiatAmountLimit)) {
            "circle $circleId lost its assignable merchant before placement"
        }

        val relay = relayIdentityStore.getOrCreate()
        val placeHash =
            account.submitter.sendTransaction(
                to = network.diamondAddress,
                data =
                    DiamondCalls.placeOrderCalldata(
                        PlaceOrderArgs(
                            relayPubKeyEthCrypto = relay.publicKeyHex,
                            usdcAmount = quote.netUsdc,
                            recipientAddress = account.address,
                            orderType = OrderType.BUY,
                            currency = quote.currency,
                            circleId = circleId,
                            fiatAmountLimit = fiatAmountLimit,
                        ),
                    ),
            )
        // The handle is recorded before the receipt: a process killed here still has a live order,
        // and the hash is the only way back to its id.
        emit(OnrampStatus.Placing(id = placeHash.hex))
        val receipt = account.submitter.awaitReceipt(placeHash)
        require(receipt.success) { "placeOrder reverted" }

        val orderId =
            OrderEvents.parseOrderIdFromReceipt(receipt, network.diamondAddress, account.address)
                ?: error("placeOrder receipt carried no OrderPlaced log for this account")
        emit(OnrampStatus.AwaitingMerchant(id = placeHash.hex, orderId = orderId.toString()))

        if (screened is ScreeningResult.Approved) {
            linkScreeningRecord(screened.activityLogId, orderId, account)
        }
        return orderId
    }

    private suspend fun selectCircle(
        quote: OnrampQuote,
        user: Address,
        fiatAmountLimit: Usdc6,
    ): BigInteger {
        val currencyHex = "0x" + AbiEncoder.bytes32String(quote.currency.code).value.toHex()
        val circles = subgraph.circlesForRouting(currencyHex)
        return router
            .selectCircleForOrder(circles, currencyHex) { id ->
                validateCircle(id.value, quote, user, fiatAmountLimit)
            }.value
    }

    private suspend fun validateCircle(
        circleId: BigInteger,
        quote: OnrampQuote,
        user: Address,
        fiatAmountLimit: Usdc6,
    ): Boolean {
        val ret =
            rpc.ethCall(
                to = network.diamondAddress,
                data =
                    DiamondCalls.getAssignableMerchantsFromCircleCalldata(
                        circleId = circleId,
                        assignUpTo = ASSIGN_UP_TO,
                        currency = quote.currency,
                        user = user,
                        usdtAmount = quote.netUsdc,
                        fiatAmount = fiatAmountLimit,
                        orderType = OrderType.BUY,
                    ),
            )
        return OrderReader.decodeAddressArrayNonEmpty(ret)
    }

    // ---- screening ----

    private sealed interface ScreeningResult {
        data class Approved(
            val activityLogId: JsonElement
        ) : ScreeningResult

        data object Rejected : ScreeningResult

        /** Reached nothing, or was answered badly. The order still places; nothing to link. */
        data object Unavailable : ScreeningResult
    }

    @Suppress("ReturnCount")
    private suspend fun screen(
        quote: OnrampQuote,
        account: SubmittingAccount,
        fiatAmountLimit: Usdc6,
    ): ScreeningResult {
        val client = screening ?: return ScreeningResult.Unavailable
        val outcome =
            try {
                client.screenBuyOrder(
                    signer = screeningSigner(account),
                    order =
                        OnrampScreeningOrder(
                            cryptoAmount = quote.grossUsdc,
                            fiatAmount = fiatAmountLimit,
                            currency = quote.currency,
                            recipientAddress = account.address,
                            fee = quote.feeUsdc,
                            amountAfterFee = quote.netUsdc,
                            paymentMethod = quote.currency.paymentMethodName(),
                            estimatedProcessingTimeSeconds = readProcessingTimeOrNull(),
                        ),
                    country = country,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Exception) {
                // Fail-open, and only here: a screening service we cannot reach must not stop an
                // order, but an explicit rejection must.
                return ScreeningResult.Unavailable
            }
        return when (outcome) {
            is OnrampScreeningOutcome.Approved -> ScreeningResult.Approved(outcome.activityLogId)
            OnrampScreeningOutcome.Rejected -> ScreeningResult.Rejected
            OnrampScreeningOutcome.Unavailable -> ScreeningResult.Unavailable
        }
    }

    /** Fire-and-forget by design: the order stands whether or not the record can be linked to it. */
    private suspend fun linkScreeningRecord(
        activityLogId: JsonElement,
        orderId: BigInteger,
        account: SubmittingAccount,
    ) {
        try {
            screening?.linkOrder(screeningSigner(account), activityLogId, orderId)
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            // Logged nowhere the user can see: the order is placed and paying it is unaffected.
        }
    }

    private suspend fun screeningSigner(account: SubmittingAccount) =
        OnrampScreeningSigner(
            // The EOA signs, because a smart account cannot produce an EIP-191 signature.
            signingKey = accountProvider.nextOfframpAccount(),
            subject = account.address,
        )

    // ---- waiting ----

    /**
     * One loop for the whole tail of the order. `placed` polls fast because a merchant accepts in
     * 20–90 seconds; `paid` polls slower because settlement is a human on the other side. Both are
     * bounded: an order nobody comes for ends in an explicit timeout with a cancel available, not
     * in a spinner that runs for as long as the screen is open.
     */
    @Suppress("ReturnCount")
    private suspend fun FlowCollector<OnrampStatus>.watch(
        orderId: BigInteger,
        account: SubmittingAccount,
        fromPaid: Boolean,
        /**
         * The `paidBuyOrder` transaction, when this wait began with one. Only [confirmPaid] has it:
         * a cold-start [resume] never sent it and cannot recover it from the order, so null there
         * is the honest answer rather than a hash guessed from the chain.
         */
        paidTx: String? = null,
    ) {
        val handle = orderId.toString()
        var attempts = 0
        var announcedPayment = false
        while (true) {
            val snapshot = readOrder(orderId)
            when (snapshot?.status) {
                OrderStatus.COMPLETED -> {
                    emit(completed(snapshot, handle, account.address, paidTx))
                    return
                }

                OrderStatus.CANCELLED -> {
                    emit(OnrampStatus.Cancelled(handle, handle))
                    return
                }

                OrderStatus.ACCEPTED -> {
                    if (!announcedPayment) {
                        emit(awaitingPayment(orderId, snapshot, handle))
                        announcedPayment = true
                    }
                    // A resting state: nothing moves until the user pays and confirms it.
                    if (!fromPaid) return
                }

                OrderStatus.PAID -> {
                    emit(OnrampStatus.AwaitingSettlement(handle, handle))
                }

                OrderStatus.PLACED -> {
                    emit(OnrampStatus.AwaitingMerchant(handle, handle))
                }

                null -> {
                    Unit
                }
            }

            val settling = fromPaid || snapshot?.status == OrderStatus.PAID
            val cap = if (settling) settlePollAttempts else acceptPollAttempts
            if (attempts >= cap) {
                emit(timedOut(snapshot?.status, settling, handle, orderId))
                return
            }
            attempts++
            delay(if (settling) settlePollMillis else acceptPollMillis)
        }
    }

    /**
     * Why the wait ended, told apart by what the chain actually said — because the answer decides
     * whether the checkpoint survives, and the checkpoint is what pays the ZEC out later.
     *
     * "No merchant came" is the only one of these that may forget the order, and it is the only one
     * where nothing of the user's has moved.
     */
    private suspend fun timedOut(
        status: OrderStatus?,
        settling: Boolean,
        handle: String,
        orderId: BigInteger,
    ): OnrampStatus {
        val phase = if (settling) OnrampPhase.AWAITING_SETTLEMENT else OnrampPhase.AWAITING_MERCHANT
        return when {
            // Every poll failed. That says nothing about the order — it is very probably still
            // running — so this must not be the branch that discards it.
            status == null -> {
                OnrampStatus.Failed(OnrampFailureCode.NETWORK_UNAVAILABLE, phase, handle, handle)
            }

            // The user has paid. The merchant is late, not absent, and the order can still complete
            // long after this screen gives up watching it.
            settling -> {
                OnrampStatus.Failed(OnrampFailureCode.SETTLEMENT_PENDING, phase, handle, handle)
            }

            // The decision is the contract's, not our clock's: a keeper sweeps expired orders, so
            // one can sit in `placed` a while before it flips.
            status == OrderStatus.PLACED && isExpiredOnChain(orderId) -> {
                OnrampStatus.Failed(OnrampFailureCode.ORDER_EXPIRED, phase, handle, handle)
            }

            else -> {
                OnrampStatus.Failed(OnrampFailureCode.NO_MERCHANT, phase, handle, handle)
            }
        }
    }

    /**
     * [paidTx] is the user's own on-chain payment confirmation, not the merchant's settlement — the
     * custodial route's `paidTx` came off the service's order record and meant the latter. It is
     * still the transaction this wallet sent and the one worth linking to an explorer; label it as
     * the payment, never as the settlement.
     */
    private suspend fun completed(
        snapshot: OrderSnapshot,
        handle: String,
        recipient: Address,
        paidTx: String?,
    ): OnrampStatus {
        // The order tuple never carries the settled amounts; they live in their own read and are
        // zero until the merchant completes. Falling back to the placed figures keeps the receipt
        // honest rather than showing a zero.
        val settled = additionalDetails(snapshot.orderId)
        return OnrampStatus.Completed(
            id = handle,
            orderId = handle,
            netUsdc = settled?.actualUsdcAmount?.takeIf { it > Usdc6.ZERO } ?: snapshot.usdcAmount,
            fiatAmount = settled?.actualFiatAmount?.takeIf { it > Usdc6.ZERO } ?: snapshot.fiatAmount,
            paidTx = paidTx,
            recipientAddress = recipient,
        )
    }

    /** The merchant's settled fiat, once there is one. Null while the order is still being filled. */
    private suspend fun settledFiat(orderId: BigInteger): Usdc6? =
        additionalDetails(orderId)?.actualFiatAmount?.takeIf { it > Usdc6.ZERO }

    private suspend fun additionalDetails(orderId: BigInteger): OrderFeeDetails? =
        try {
            rpc.getAdditionalOrderDetails(network.diamondAddress, orderId)
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            null
        }

    /**
     * The merchant's handle is only readable here, on this device, with this wallet's relay key.
     * A failure to decrypt means the key is gone and the order cannot be paid — surfaced as a
     * failure rather than as a partial or placeholder address, which would send real money to
     * nobody.
     */
    private suspend fun awaitingPayment(
        orderId: BigInteger,
        snapshot: OrderSnapshot,
        handle: String,
    ): OnrampStatus {
        // Only ever from the order itself: the subgraph returns this field empty, and it is the
        // whole payment step.
        val ciphertext = snapshot.encryptedUserUpi.ifBlank { snapshot.encryptedMerchantUpi }
        val payTo =
            PaymentAddressDecryptor.decrypt(ciphertext, relayIdentityStore.get())
                ?: return OnrampStatus.Failed(
                    OnrampFailureCode.UPSTREAM_FAILED,
                    OnrampPhase.AWAITING_PAYMENT,
                    handle,
                    handle,
                )
        orderRecipientUpiCache.put(handle, payTo)
        // What the order says to pay, not what the user typed — the two differ by the fee.
        val fiat = settledFiat(orderId) ?: snapshot.fiatAmount
        return OnrampStatus.AwaitingPayment(
            id = handle,
            orderId = handle,
            instruction = paymentInstruction(payTo, orderId, fiat, snapshot.corridor()),
            fiatAmount = fiat,
            expiresAtMillis = orderExpiresAtMillis(orderId),
        )
    }

    /**
     * How a corridor is payable differs in kind, not in formatting: INR is a UPI intent the phone
     * can open, and the EMVCo corridors hand over a complete QR payload that is unpayable as text
     * on a screen.
     *
     * Everything else is rendered as the merchant's handle verbatim. Venezuela in particular is
     * *not* split into labelled fields here: its payload is a base64 envelope followed by `?` and
     * a suffix, and the whole raw string is what the merchant is paid at (`PagoMovilQrParser`,
     * which is byte-compatible with p2p's own parser). Splitting it on a guessed separator would
     * show three boxes of garbage. If a corridor really does need labelled fields, read one live
     * order first.
     */
    private fun paymentInstruction(
        payTo: String,
        orderId: BigInteger,
        fiat: Usdc6,
        currency: CurrencyCode,
    ): OnrampPaymentInstruction =
        when (currency) {
            CurrencyCode.Inr -> {
                OnrampPaymentInstruction.Upi(
                    address = payTo,
                    intentUrl = UpiPayUri.buildBuyIntent(payTo, orderId, fiat, currency.code),
                    amount = UpiPayUri.twoDecimalAmount(fiat),
                )
            }

            // Already a complete EMVCo payload: rendered as a QR, never as the string itself.
            CurrencyCode.Pen, CurrencyCode.Php, CurrencyCode.Bob -> {
                OnrampPaymentInstruction.Qr(payTo)
            }

            else -> {
                OnrampPaymentInstruction.Plain(payTo)
            }
        }

    /**
     * The corridor as the chain records it on the order, rather than as the caller remembers it —
     * a resumed order has no caller left to ask.
     */
    private fun OrderSnapshot.corridor(): CurrencyCode = corridorFromBytes32(currencyHex) ?: CurrencyCode.Inr

    private suspend fun readOrder(orderId: BigInteger): OrderSnapshot? =
        try {
            orderReader.fetchOrder(orderId)
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            // A single dropped poll must not end an order whose USDC is already committed.
            null
        }

    private suspend fun readPriceConfig(currency: CurrencyCode): PriceConfig =
        PriceConfigDecoder.decode(
            rpc.ethCall(to = network.diamondAddress, data = DiamondCalls.getPriceConfigCalldata(currency)),
        )

    private suspend fun readUsdc(calldata: ByteArray): Usdc6 =
        Usdc6(AbiDecoder(rpc.ethCall(to = network.diamondAddress, data = calldata)).also { it.requireWords(1) }.uint(0))

    private suspend fun readBuyLimit(user: Address, currency: CurrencyCode): Usdc6 {
        val ret =
            rpc.ethCall(
                to = network.diamondAddress,
                data = ReputationCalls.userTxLimitCalldata(user, currency),
            )
        return ReputationCalls.decodeUserTxLimits(ret).buy
    }

    private suspend fun isCorridorOpen(currency: CurrencyCode): Boolean =
        readExchangeStatus() && readCurrencySupported(currency)

    private suspend fun readExchangeStatus(): Boolean = readBoolean(DiamondCalls.getExchangeStatusCalldata())

    private suspend fun readCurrencySupported(currency: CurrencyCode): Boolean =
        readBoolean(DiamondCalls.isCurrencySupportedCalldata(currency))

    private suspend fun readBoolean(calldata: ByteArray): Boolean {
        val ret = rpc.ethCall(to = network.diamondAddress, data = calldata)
        return ret.isNotEmpty() && BigInteger(1, ret).signum() != 0
    }

    private suspend fun readProcessingTimeOrNull(): Long? =
        try {
            val ret = rpc.ethCall(to = network.diamondAddress, data = DiamondCalls.getProcessingTimeCalldata())
            if (ret.isEmpty()) null else BigInteger(1, ret).toLong()
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            null
        }

    /**
     * When the contract stops accepting payment for this order, as epoch millis.
     *
     * This is the whole reason the payment screen can close itself. Polling stops once an order is
     * accepted, so nothing else is watching the window; without a deadline here the screen stays
     * payable for as long as it is open, and a user who comes back late sends fiat into an order
     * the contract will no longer settle.
     *
     * The tuple counts in seconds and the screen counts in millis. Null when the read fails or the
     * contract names no deadline — no countdown, rather than one that reads as already passed.
     */
    private suspend fun orderExpiresAtMillis(orderId: BigInteger): Long? =
        try {
            val ret =
                rpc.ethCall(
                    to = network.diamondAddress,
                    data = DiamondCalls.getOrderExpiresAtCalldata(orderId),
                )
            if (ret.isEmpty()) null else BigInteger(1, ret).toLong().takeIf { it > 0L }?.times(MILLIS_PER_SECOND)
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            null
        }

    private suspend fun isExpiredOnChain(orderId: BigInteger): Boolean =
        try {
            readBoolean(DiamondCalls.isOrderExpiredCalldata(orderId))
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            false
        }

    /**
     * The order id, recovered from the placement hash the checkpoint recorded before the receipt
     * existed. A cold start after a killed process lands here.
     */
    private suspend fun recoverOrderId(checkpoint: OnrampCheckpoint, account: SubmittingAccount): BigInteger? =
        receiptOrNull(checkpoint.id, account)
            // A placement that reverted left no order to recover, and its receipt carries no log.
            ?.takeIf { it.success }
            ?.let { OrderEvents.parseOrderIdFromReceipt(it, network.diamondAddress, account.address) }

    /**
     * ☠ Through the submitter, never through [rpc]. The handle a placement returns is a *userOpHash*
     * on the 4337 route, and no node has heard of it — `eth_getTransactionReceipt` answers null for
     * a perfectly good order. Only the bundler can resolve it, and the submitter is the way in.
     *
     * Getting this wrong is invisible until a process dies mid-placement: every cold-start recovery
     * then reports "no order", the checkpoint is cleared, and a live on-chain order is orphaned.
     */
    private suspend fun receiptOrNull(hash: String, account: SubmittingAccount): TransactionReceipt? =
        try {
            account.submitter.awaitReceipt(TxHash.fromHex(hash))
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            null
        }

    // ---- plumbing ----

    private fun failed(code: OnrampFailureCode, phase: OnrampPhase, checkpoint: OnrampCheckpoint) =
        OnrampStatus.Failed(code, phase, checkpoint.id, checkpoint.orderId)

    private fun String.toBigIntegerOrNull(): BigInteger? = runCatching { BigInteger(this) }.getOrNull()

    private fun Flow<OnrampStatus>.guarded(
        phase: OnrampPhase,
        id: String?,
        orderId: String?,
    ): Flow<OnrampStatus> =
        flow {
            try {
                collect { emit(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (
                // Every failure on this path has to reach the user as a status rather than as a
                // crashed flow; the revert decoding that names it happens in the UI layer.
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                emit(OnrampStatus.Failed(classify(e), phase, id, orderId))
            }
        }

    private fun classify(e: Exception): OnrampFailureCode {
        val message = e.message.orEmpty()
        return when {
            BUY_LIMIT_SELECTOR in message || NO_REPUTATION_SELECTOR in message -> OnrampFailureCode.CAP_EXCEEDED
            NO_MERCHANT_SELECTOR in message -> OnrampFailureCode.NO_MERCHANT
            EXPIRED_SELECTOR in message -> OnrampFailureCode.ORDER_EXPIRED
            else -> OnrampFailureCode.UPSTREAM_FAILED
        }
    }

    private fun CurrencyCode.paymentMethodName(): String = if (this == CurrencyCode.Inr) "UPI" else code

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val QUOTE_ID_PREFIX = "direct-"
        const val QUOTE_TTL_MILLIS = 90_000L

        /** 3s while placed: merchants accept in 20–90 seconds. */
        const val ACCEPT_POLL_MILLIS = 3_000L

        /** 10s once paid: the other side is a person moving money. */
        const val SETTLE_POLL_MILLIS = 10_000L

        /** ~4 minutes. Past that no merchant is coming. */
        const val ACCEPT_POLL_ATTEMPTS = 80

        /** ~15 minutes of headroom for settlement. */
        const val SETTLE_POLL_ATTEMPTS = 90

        val ASSIGN_UP_TO: BigInteger = bigIntegerValueOf(3L)

        const val BUY_LIMIT_SELECTOR = "0x91da284f"
        const val NO_REPUTATION_SELECTOR = "0x071ea33c"
        const val NO_MERCHANT_SELECTOR = "0x5d04ff4c"
        const val EXPIRED_SELECTOR = "0xc56873ba"
    }
}

/**
 * Decodes the Diamond's `bytes32` currency word back to a corridor: ASCII, NUL-padded to the
 * right. Kept out of the driver so the round trip is testable on its own — a resumed order that
 * reads back as the wrong corridor would build a payment intent in the wrong currency.
 */
internal fun corridorFromBytes32(currencyHex: String): CurrencyCode? =
    CurrencyCode.fromCodeOrNull(currencyHex.hexToBytes().decodeToString().trimEnd(NUL_CHAR))

private const val NUL_CHAR = '\u0000'
