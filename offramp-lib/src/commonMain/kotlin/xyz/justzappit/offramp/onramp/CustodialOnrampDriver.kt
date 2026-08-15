// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6

/**
 * Drives an onramp order entirely through the operator service: this device places nothing on-chain
 * and reads neither the chain nor the subgraph. Every transition comes from `GET /v1/orders/{id}`.
 */
class CustodialOnrampDriver(
    private val client: CustodialOnrampClient,
    private val deviceSignals: OnrampDeviceSignalsProvider,
    private val recipientProvider: OnrampRecipientProvider,
    private val fallbackCurrency: CurrencyCode = CurrencyCode.Inr,
    private val paymentPollMillis: Long = DEFAULT_PAYMENT_POLL_MILLIS,
    private val settlementPollMillis: Long = DEFAULT_SETTLEMENT_POLL_MILLIS,
    private val maxPolls: Int = DEFAULT_MAX_POLLS,
) : OnrampDriver {
    override suspend fun limits(currency: CurrencyCode): OnrampLimits =
        runCatching { client.config(currency).toLimits() }.getOrDefault(OnrampLimits.DISABLED)

    override suspend fun recipientAddress(): Address = recipientProvider.recipient()

    override suspend fun quote(
        fiatAmount: Usdc6,
        currency: CurrencyCode,
    ): OnrampQuote = client.quote(fiatAmount, currency).toQuote(currency)

    override fun start(quote: OnrampQuote): Flow<OnrampStatus> =
        flow {
            emit(OnrampStatus.Placing(id = null))
            val created =
                client.createOrder(
                    quoteId = quote.quoteId,
                    recipient = recipientProvider.recipient(),
                    device = deviceSignals.collect(),
                )
            emitAll(watch(created.toOrder(quote.currency)))
        }.guarded(OnrampPhase.PLACING, id = null, orderId = null)

    override fun confirmPaid(checkpoint: OnrampCheckpoint): Flow<OnrampStatus> =
        flow {
            emit(OnrampStatus.ConfirmingPaid(checkpoint.id, checkpoint.orderId))
            emitAll(watch(client.markPaid(checkpoint.id).toOrder(fallbackCurrency)))
        }.guarded(OnrampPhase.CONFIRMING_PAID, checkpoint.id, checkpoint.orderId)

    override fun resume(checkpoint: OnrampCheckpoint): Flow<OnrampStatus> =
        flow {
            emitAll(watch(client.order(checkpoint.id).toOrder(fallbackCurrency)))
        }.guarded(checkpoint.phase, checkpoint.id, checkpoint.orderId)

    override fun cancel(checkpoint: OnrampCheckpoint): Flow<OnrampStatus> =
        flow {
            emit(client.cancel(checkpoint.id).toOrder(fallbackCurrency).toStatus())
        }.guarded(checkpoint.phase, checkpoint.id, checkpoint.orderId)

    /**
     * Polls until the service reports a terminal phase. [OnrampPhase.AWAITING_PAYMENT] is a resting
     * state waiting on the user, not on the service, so polling stops there and only resumes once
     * `paid` moves it on.
     *
     * [maxPolls] bounds a service wedged in a non-terminal phase, which would otherwise poll for as
     * long as the screen is open. Giving up is reported as transient so the checkpoint survives and
     * reopening the screen resumes the order.
     */
    private fun watch(initial: OnrampOrder): Flow<OnrampStatus> =
        flow {
            var latest = initial
            var polls = 0
            while (true) {
                emit(latest.toStatus())
                if (latest.phase.isTerminal || latest.phase == OnrampPhase.AWAITING_PAYMENT) return@flow
                if (polls >= maxPolls) {
                    emit(
                        OnrampStatus.Failed(
                            OnrampFailureCode.OPERATOR_UNAVAILABLE,
                            latest.phase,
                            latest.id,
                            latest.orderId,
                        ),
                    )
                    return@flow
                }
                polls++
                delay(latest.phase.pollInterval())
                latest = client.order(latest.id).toOrder(latest.currency)
            }
        }

    private fun OnrampPhase.pollInterval(): Long =
        if (this == OnrampPhase.AWAITING_SETTLEMENT || this == OnrampPhase.CONFIRMING_PAID) {
            settlementPollMillis
        } else {
            paymentPollMillis
        }

    private fun Flow<OnrampStatus>.guarded(
        phase: OnrampPhase,
        id: String?,
        orderId: String?,
    ): Flow<OnrampStatus> =
        flow {
            try {
                collect { emit(it) }
            } catch (e: OnrampException) {
                emit(OnrampStatus.Failed(e.code, phase, id, orderId))
            }
        }

    private companion object {
        const val DEFAULT_PAYMENT_POLL_MILLIS = 3_000L
        const val DEFAULT_SETTLEMENT_POLL_MILLIS = 5_000L

        // ~15 minutes at the 3s cadence, comfortably past the 20-90s a merchant normally takes.
        const val DEFAULT_MAX_POLLS = 300
    }
}

internal fun OnrampOrder.toStatus(): OnrampStatus =
    when (phase) {
        OnrampPhase.PLACING -> {
            OnrampStatus.Placing(id)
        }

        OnrampPhase.AWAITING_MERCHANT -> {
            OnrampStatus.AwaitingMerchant(id, orderId)
        }

        OnrampPhase.AWAITING_PAYMENT -> {
            awaitingPaymentOrFailed()
        }

        OnrampPhase.CONFIRMING_PAID -> {
            OnrampStatus.ConfirmingPaid(id, orderId)
        }

        OnrampPhase.AWAITING_SETTLEMENT -> {
            OnrampStatus.AwaitingSettlement(id, orderId)
        }

        OnrampPhase.COMPLETED -> {
            completedOrFailed()
        }

        OnrampPhase.CANCELLED -> {
            OnrampStatus.Cancelled(id, orderId)
        }

        OnrampPhase.EXPIRED -> {
            OnrampStatus.Failed(failureCode ?: OnrampFailureCode.ORDER_EXPIRED, phase, id, orderId)
        }

        OnrampPhase.FAILED -> {
            OnrampStatus.Failed(failureCode ?: OnrampFailureCode.UNKNOWN, phase, id, orderId)
        }
    }

/**
 * An order the service reports as payable but without an instruction or an amount is one the app
 * cannot show a payable figure for, so it is surfaced as a failure rather than as a zero.
 */
private fun OnrampOrder.awaitingPaymentOrFailed(): OnrampStatus {
    val instruction = paymentInstruction
    val fiat = fiatAmount
    return if (instruction == null || fiat == null) {
        upstreamFailed()
    } else {
        OnrampStatus.AwaitingPayment(id, orderId, instruction, fiat, expiresAtMillis)
    }
}

private fun OnrampOrder.completedOrFailed(): OnrampStatus {
    val net = netUsdc
    val fiat = fiatAmount
    val recipient = recipientAddress
    return if (net == null || fiat == null || recipient == null) {
        upstreamFailed()
    } else {
        OnrampStatus.Completed(id, orderId, net, fiat, paidTx, recipient)
    }
}

private fun OnrampOrder.upstreamFailed(): OnrampStatus =
    OnrampStatus.Failed(OnrampFailureCode.UPSTREAM_FAILED, phase, id, orderId)
