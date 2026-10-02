package co.electriccoin.zcash.ui.common.invest.demo

import co.electriccoin.zcash.ui.common.invest.model.InvestAsset
import co.electriccoin.zcash.ui.common.invest.model.PreparedSell
import co.electriccoin.zcash.ui.common.invest.model.SellAmount
import co.electriccoin.zcash.ui.common.invest.model.SellEstimate
import co.electriccoin.zcash.ui.common.invest.model.SellProgress
import co.electriccoin.zcash.ui.common.invest.provider.InvestApiException
import co.electriccoin.zcash.ui.common.invest.repository.InvestRepository
import co.electriccoin.zcash.ui.common.invest.repository.InvestSellRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * Selling in the demo build, on [DemoInvestEngine]'s holdings: no intent is generated or signed, and the ZEC a
 * completed sale reports is never actually sent to the wallet.
 */
internal class DemoInvestSellRepository(
    private val engine: DemoInvestEngine,
    private val controls: InvestDemoControls,
    private val clock: Clock = Clock.System,
    private val stepMillis: Long = DemoInvestEngine.STEP_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : InvestSellRepository {
    override val pendingSells: Flow<List<String>> =
        engine.pendingTrades.map { list -> list.orEmpty().filter { it.isSale }.map { it.depositAddress } }

    override suspend fun estimateSell(
        asset: InvestAsset,
        amount: SellAmount,
    ): SellEstimate {
        delay(LOAD_MS)
        return when (val order = order(asset, amount)) {
            is Order.Refused -> order.estimate
            is Order.Priced -> order.estimate
        }
    }

    override suspend fun prepareSell(
        asset: InvestAsset,
        amount: SellAmount,
    ): PreparedSell {
        check(!engine.hasTrade(asset.assetId)) { DemoInvestEngine.TRADE_IN_FLIGHT }
        delay(LOAD_MS)
        val priced =
            when (val order = order(asset, amount)) {
                is Order.Priced -> order.estimate
                is Order.Refused -> throw order.toException()
            }
        val expiresAt = clock.now() + PRICE_HOLD
        val address = engine.newAddress("demo-sell")
        return PreparedSell(
            asset = asset,
            units = priced.unitsIn,
            usdIn = priced.usdIn,
            zecOutExpected = priced.zecOut,
            zecOutMin = priced.zecOut.multiply(MIN_OUT_SHARE).setScale(DemoInvestEngine.ZEC_SCALE, RoundingMode.DOWN),
            feesUsd = priced.feesUsd,
            withdrawFeeZec = priced.withdrawFeeZec,
            etaSeconds = priced.etaSeconds,
            expiresAt = expiresAt,
            signedMessage = signedMessage(asset, priced.unitsIn, address),
            depositAddress = address,
            baseUnits =
                priced.unitsIn
                    .movePointRight(TOKEN_DECIMALS)
                    .toBigInteger()
                    .toString(),
            intentDeadline = expiresAt,
        )
    }

    override suspend fun executeSell(prepared: PreparedSell): String {
        check(clock.now() < prepared.expiresAt) { "The price is no longer held; prepare the sale again" }
        check(prepared.units <= engine.heldUnits(prepared.asset.assetId)) { "Not enough of this stock is held" }
        val address = prepared.depositAddress
        val assetId = prepared.asset.assetId
        engine.startTrade(address, DemoInvestEngine.Trade(assetId, sell = SellProgress.Authorised(address)))
        val outcome = controls.outcome.value
        scope.launch {
            engine.step(address) { it.copy(sell = SellProgress.Selling(address)) }
            delay(stepMillis)
            val final =
                when (outcome) {
                    InvestDemoOutcome.COMPLETES -> SellProgress.Sent(address, prepared.zecOutExpected)
                    InvestDemoOutcome.REFUNDED -> SellProgress.ReturnedToAccount(address)
                    InvestDemoOutcome.NEEDS_ATTENTION -> SellProgress.NeedsAttention(address, reference = address)
                }
            if (final is SellProgress.Sent) engine.addUnits(assetId, prepared.units.negate())
            engine.finish(address) { it.copy(sell = final) }
        }
        return address
    }

    override fun observeSell(depositAddress: String): Flow<SellProgress> =
        engine.trades
            .mapNotNull { it[depositAddress]?.sell }
            .distinctUntilChanged()
            .transformWhile { progress ->
                emit(progress)
                !progress.isFinal
            }

    override suspend fun dismissSell(depositAddress: String) = engine.dismiss(depositAddress)

    private sealed interface Order {
        data class Priced(
            val estimate: SellEstimate.Priced,
        ) : Order

        data class Refused(
            val estimate: SellEstimate,
        ) : Order {
            fun toException(): Exception =
                if (estimate is SellEstimate.NoPrice) {
                    InvestApiException.NoPrice(null)
                } else {
                    IllegalStateException("The sale can't be quoted: $estimate")
                }
        }
    }

    // The same refusals as the real engine's InvestSellChecks, in the order the screen explains them.
    @Suppress("ReturnCount")
    private fun order(
        asset: InvestAsset,
        amount: SellAmount,
    ): Order {
        val held = engine.heldUnits(asset.assetId)
        if (held.signum() <= 0) return Order.Refused(SellEstimate.NothingHeld)
        if (!controls.hasLiquidity.value) return Order.Refused(SellEstimate.NoPrice)
        val price = engine.priceOf(asset)
        val units =
            when (amount) {
                SellAmount.All -> held
                is SellAmount.Units -> amount.units
                is SellAmount.Usd -> amount.value.divide(price, DemoInvestEngine.UNIT_SCALE, RoundingMode.DOWN)
            }
        val usdIn = units.multiply(price).setScale(2, RoundingMode.HALF_UP)
        val zecUsd = engine.zecUsd()
        val fees = usdIn.multiply(DemoInvestEngine.FEE_SHARE) + WITHDRAW_FEE_ZEC.multiply(zecUsd)
        val usdOut = (usdIn - fees).setScale(2, RoundingMode.HALF_UP)
        return when {
            units > held -> {
                Order.Refused(SellEstimate.ExceedsHolding(held))
            }

            amount != SellAmount.All && usdIn < InvestRepository.MINIMUM_USD -> {
                Order.Refused(SellEstimate.BelowMinimum(InvestRepository.MINIMUM_USD))
            }

            usdOut.signum() <= 0 -> {
                Order.Refused(SellEstimate.TooSmallToSell)
            }

            else -> {
                Order.Priced(
                    SellEstimate.Priced(
                        unitsIn = units,
                        usdIn = usdIn,
                        zecOut = usdOut.divide(zecUsd, DemoInvestEngine.ZEC_SCALE, RoundingMode.DOWN),
                        usdOut = usdOut,
                        feesUsd = (usdIn - usdOut).max(BigDecimal.ZERO),
                        withdrawFeeZec = WITHDRAW_FEE_ZEC,
                        etaSeconds = SELL_ETA_S,
                    ),
                )
            }
        }
    }

    private fun signedMessage(
        asset: InvestAsset,
        units: BigDecimal,
        address: String,
    ) = """{"demo":true,"intents":[{"intent":"transfer","receiver_id":"$address",""" +
        """"tokens":{"${asset.assetId}":"${units.toPlainString()}"}}]}"""

    private companion object {
        const val LOAD_MS = 600L
        const val SELL_ETA_S = 300
        const val TOKEN_DECIMALS = 18
        val PRICE_HOLD = 10.minutes
        val MIN_OUT_SHARE = BigDecimal("0.99")

        /** 1Click's fixed ZEC payout fee (64,000 zats on 2026-09-25). */
        val WITHDRAW_FEE_ZEC = BigDecimal("0.00064")
    }
}
