package co.electriccoin.zcash.ui.screen.invest

import co.electriccoin.zcash.ui.common.invest.model.BuyEstimate
import co.electriccoin.zcash.ui.common.invest.model.BuyProgress
import co.electriccoin.zcash.ui.common.invest.model.Holdings
import co.electriccoin.zcash.ui.common.invest.model.InvestAsset
import co.electriccoin.zcash.ui.common.invest.model.InvestMarket
import co.electriccoin.zcash.ui.common.invest.model.PreparedBuy
import co.electriccoin.zcash.ui.common.invest.repository.InvestRepository
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettings
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettingsRepository
import co.electriccoin.zcash.ui.common.model.SwapQuote
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import java.math.BigDecimal
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** A scripted [InvestRepository]: each call is recorded and answered by the lambda the test sets. */
internal class FakeInvestRepository : InvestRepository {
    override val market = MutableStateFlow<InvestMarket?>(null)
    override val holdings = MutableStateFlow<Holdings?>(null)
    override val pendingBuys = MutableStateFlow<List<String>>(emptyList())

    var onRefreshMarket: suspend () -> Unit = {}
    var onRefreshHoldings: suspend () -> Unit = {}
    var onEstimate: suspend (InvestAsset, BigDecimal) -> BuyEstimate = { _, _ -> BuyEstimate.NoPrice }
    var onPrepare: suspend (InvestAsset, BigDecimal) -> PreparedBuy = { _, _ -> error("no prepare scripted") }
    var onExecute: suspend (PreparedBuy) -> String = { error("no execute scripted") }
    var onObserve: (String) -> Flow<BuyProgress> = { emptyFlow() }

    var accountSupported = true
    val dismissedBuys = mutableListOf<String>()

    val estimateCalls = mutableListOf<BigDecimal>()
    val prepareCalls = mutableListOf<BigDecimal>()
    val executeCalls = mutableListOf<PreparedBuy>()
    var refreshHoldingsCalls = 0

    override suspend fun refreshMarket() = onRefreshMarket()

    override suspend fun refreshHoldings() {
        refreshHoldingsCalls++
        onRefreshHoldings()
    }

    override suspend fun estimateBuy(
        asset: InvestAsset,
        usdAmount: BigDecimal,
    ): BuyEstimate {
        estimateCalls += usdAmount
        return onEstimate(asset, usdAmount)
    }

    override suspend fun prepareBuy(
        asset: InvestAsset,
        usdAmount: BigDecimal,
    ): PreparedBuy {
        prepareCalls += usdAmount
        return onPrepare(asset, usdAmount)
    }

    override suspend fun executeBuy(prepared: PreparedBuy): String {
        executeCalls += prepared
        return onExecute(prepared)
    }

    override fun observeBuy(depositAddress: String): Flow<BuyProgress> = onObserve(depositAddress)

    override suspend fun isAccountSupported(): Boolean = accountSupported

    override suspend fun dismissBuy(depositAddress: String) {
        dismissedBuys += depositAddress
    }
}

internal class FakeInvestSettingsRepository(
    initial: InvestSettings = InvestSettings(),
) : InvestSettingsRepository {
    override val settings = MutableStateFlow(initial)
    val residenceCalls = mutableListOf<Pair<String, Boolean>>()

    override suspend fun get(): InvestSettings = settings.value

    override suspend fun setResidence(
        countryCode: String,
        qualifiedInvestor: Boolean,
    ) {
        residenceCalls += countryCode to qualifiedInvestor
        settings.value = settings.value.copy(countryCode = countryCode, qualifiedInvestor = qualifiedInvestor)
    }

    override suspend fun completeSetup() {
        settings.value = settings.value.copy(setupComplete = true)
    }
}

/** Virtual time as wall-clock time, so countdowns follow `advanceTimeBy`. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.virtualClock(start: Instant = Instant.fromEpochMilliseconds(START_MILLIS)): Clock =
    object : Clock {
        override fun now(): Instant = start + testScheduler.currentTime.milliseconds
    }

internal fun preparedBuy(
    asset: InvestAsset,
    expiresAt: Instant,
    refundFeeZec: BigDecimal? = BigDecimal("0.00032"),
) = PreparedBuy(
    asset = asset,
    zecIn = BigDecimal("0.06478"),
    unitsOutExpected = BigDecimal("0.4410"),
    unitsOutMin = BigDecimal("0.4366"),
    usdOut = BigDecimal("99.01"),
    feesUsd = BigDecimal("0.95"),
    refundFeeZec = refundFeeZec,
    etaSeconds = 470,
    expiresAt = expiresAt,
    quote = mockk<SwapQuote>(relaxed = true),
)

// 2026-09-28 (a Monday) 15:00 UTC: 11:00 in New York, inside regular hours.
internal const val START_MILLIS = 1_790_607_600_000L

// 2026-09-26 (a Saturday) 15:00 UTC: US markets closed, Ondo's 24/5 window shut.
internal const val SATURDAY_MILLIS = 1_790_434_800_000L
