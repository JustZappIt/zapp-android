// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6

/**
 * Walks the real phase machine on timers so the onramp screens can be driven without placing an
 * order or moving money. Bound only in debug builds — see `RepositoryModule`.
 */
class FakeOnrampDriver(
    private val stepDelayMillis: Long = DEFAULT_STEP_DELAY_MILLIS,
) : OnrampDriver {
    /** The demo driver serves whatever it is asked for, so every corridor this build knows buys. */
    override suspend fun buyCorridors(): Set<CurrencyCode> = CurrencyCode.entries.toSet()

    override suspend fun limits(currency: CurrencyCode): OnrampLimits =
        OnrampLimits(
            enabled = true,
            currency = currency,
            minFiat = Usdc6.ofMicros(DEMO_MIN_FIAT_MICROS),
            maxFiat = Usdc6.ofMicros(DEMO_MAX_FIAT_MICROS),
            perUserDailyFiat = Usdc6.ofMicros(DEMO_DAILY_FIAT_MICROS),
        )

    override suspend fun recipientAddress(): Address = Address.parse(DEMO_RECIPIENT)

    override suspend fun quote(
        fiatAmount: Usdc6,
        currency: CurrencyCode,
    ): OnrampQuote =
        OnrampQuote(
            quoteId = DEMO_QUOTE_ID,
            currency = currency,
            fiatAmount = fiatAmount,
            grossUsdc = Usdc6.ofMicros(DEMO_GROSS_USDC_MICROS),
            feeUsdc = Usdc6.ofMicros(DEMO_FEE_USDC_MICROS),
            netUsdc = Usdc6.ofMicros(DEMO_NET_USDC_MICROS),
            buyPrice = Usdc6.ofMicros(DEMO_BUY_PRICE_MICROS),
            expiresAtMillis = 0L,
        )

    override fun start(quote: OnrampQuote): Flow<OnrampStatus> =
        flow {
            emit(OnrampStatus.Placing(DEMO_ID))
            delay(stepDelayMillis)
            emit(OnrampStatus.AwaitingMerchant(DEMO_ID, DEMO_ORDER_ID))
            delay(stepDelayMillis)
            emit(
                OnrampStatus.AwaitingPayment(
                    id = DEMO_ID,
                    orderId = DEMO_ORDER_ID,
                    instruction = demoInstruction(quote.fiatAmount),
                    fiatAmount = quote.fiatAmount,
                    expiresAtMillis = null,
                ),
            )
        }

    override fun confirmPaid(checkpoint: OnrampCheckpoint): Flow<OnrampStatus> =
        flow {
            emit(OnrampStatus.ConfirmingPaid(checkpoint.id, checkpoint.orderId))
            delay(stepDelayMillis)
            emit(OnrampStatus.AwaitingSettlement(checkpoint.id, checkpoint.orderId))
            delay(stepDelayMillis)
            emit(
                OnrampStatus.Completed(
                    id = checkpoint.id,
                    orderId = checkpoint.orderId,
                    netUsdc = Usdc6.ofMicros(DEMO_NET_USDC_MICROS),
                    fiatAmount = Usdc6.ofMicros(DEMO_FIAT_MICROS),
                    paidTx = null,
                    recipientAddress = Address.parse(DEMO_RECIPIENT),
                ),
            )
        }

    override fun resume(checkpoint: OnrampCheckpoint): Flow<OnrampStatus> =
        flow {
            when (checkpoint.phase) {
                OnrampPhase.AWAITING_PAYMENT -> {
                    emit(
                        OnrampStatus.AwaitingPayment(
                            id = checkpoint.id,
                            orderId = checkpoint.orderId,
                            instruction = demoInstruction(Usdc6.ofMicros(DEMO_FIAT_MICROS)),
                            fiatAmount = Usdc6.ofMicros(DEMO_FIAT_MICROS),
                            expiresAtMillis = null,
                        ),
                    )
                }

                OnrampPhase.CONFIRMING_PAID, OnrampPhase.AWAITING_SETTLEMENT -> {
                    emit(OnrampStatus.AwaitingSettlement(checkpoint.id, checkpoint.orderId))
                }

                else -> {
                    emit(OnrampStatus.AwaitingMerchant(checkpoint.id, checkpoint.orderId))
                }
            }
        }

    override fun cancel(checkpoint: OnrampCheckpoint): Flow<OnrampStatus> =
        flow { emit(OnrampStatus.Cancelled(checkpoint.id, checkpoint.orderId)) }

    // The payee name is spaced, as a real merchant's is, so the demo exercises the escaping the
    // service's own payloads need rather than a URI that happens to be clean.
    private fun demoInstruction(fiatAmount: Usdc6): OnrampPaymentInstruction.Upi {
        val amount = fiatAmount.toFiatString(CurrencyCode.Inr)
        return OnrampPaymentInstruction.Upi(
            address = DEMO_VPA,
            intentUrl = "upi://pay?pa=$DEMO_VPA&pn=$DEMO_PAYEE&am=$amount&cu=INR&tr=$DEMO_ORDER_ID",
            amount = amount,
        )
    }

    private companion object {
        const val DEFAULT_STEP_DELAY_MILLIS = 2_000L
        const val DEMO_ID = "00000000-0000-4000-8000-000000000000"
        const val DEMO_ORDER_ID = "659007"
        const val DEMO_QUOTE_ID = "11111111-1111-4111-8111-111111111111"
        const val DEMO_RECIPIENT = "0x000000000000000000000000000000000000dEaD"
        const val DEMO_VPA = "demo.merchant@upi"
        const val DEMO_PAYEE = "Zapp Demo Merchant"
        const val DEMO_FIAT_MICROS = 104_999_902L
        const val DEMO_MIN_FIAT_MICROS = 100_000_000L
        const val DEMO_MAX_FIAT_MICROS = 500_000_000L
        const val DEMO_DAILY_FIAT_MICROS = 1_000_000_000L
        const val DEMO_GROSS_USDC_MICROS = 960_153L
        const val DEMO_FEE_USDC_MICROS = 50_000L
        const val DEMO_NET_USDC_MICROS = 910_153L
        const val DEMO_BUY_PRICE_MICROS = 104_150_000L
    }
}
