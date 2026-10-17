package co.electriccoin.zcash.ui.screen.invest

import co.electriccoin.zcash.ui.common.invest.model.InvestAsset
import co.electriccoin.zcash.ui.common.invest.model.PreparedSell
import co.electriccoin.zcash.ui.common.invest.model.SellAmount
import co.electriccoin.zcash.ui.common.invest.model.SellEstimate
import co.electriccoin.zcash.ui.common.invest.model.SellProgress
import co.electriccoin.zcash.ui.common.invest.repository.InvestSellRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.math.BigDecimal
import kotlin.time.Instant

/** A scripted [InvestSellRepository]: each call is recorded and answered by the lambda the test sets. */
internal class FakeInvestSellRepository : InvestSellRepository {
    override val pendingSells: Flow<List<String>> = MutableStateFlow(emptyList())

    var onEstimate: suspend (InvestAsset, SellAmount) -> SellEstimate = { _, _ -> SellEstimate.NoPrice }
    var onPrepare: suspend (InvestAsset, SellAmount) -> PreparedSell = { _, _ -> error("no prepare scripted") }
    var onExecute: suspend (PreparedSell) -> String = { error("no execute scripted") }
    var onObserve: (String) -> Flow<SellProgress> = { emptyFlow() }

    val estimateCalls = mutableListOf<SellAmount>()
    val prepareCalls = mutableListOf<SellAmount>()
    val executeCalls = mutableListOf<PreparedSell>()
    val dismissed = mutableListOf<String>()

    override suspend fun estimateSell(
        asset: InvestAsset,
        amount: SellAmount,
    ): SellEstimate {
        estimateCalls += amount
        return onEstimate(asset, amount)
    }

    override suspend fun prepareSell(
        asset: InvestAsset,
        amount: SellAmount,
    ): PreparedSell {
        prepareCalls += amount
        return onPrepare(asset, amount)
    }

    override suspend fun executeSell(prepared: PreparedSell): String {
        executeCalls += prepared
        return onExecute(prepared)
    }

    override fun observeSell(depositAddress: String): Flow<SellProgress> = onObserve(depositAddress)

    override suspend fun dismissSell(depositAddress: String) {
        dismissed += depositAddress
    }

    override fun clearWalletData() = Unit
}

/**
 * A prepared sale as the engine would hand it over. Only the engine makes these in the app; the constructor is
 * internal to ui-lib, which is how this fake can build one.
 */
internal fun preparedSell(
    asset: InvestAsset,
    expiresAt: Instant,
    units: BigDecimal = BigDecimal("0.4410"),
) = PreparedSell(
    asset = asset,
    units = units,
    usdIn = BigDecimal("98.99"),
    zecOutExpected = BigDecimal("0.0634"),
    zecOutMin = BigDecimal("0.0628"),
    feesUsd = BigDecimal("1.12"),
    withdrawFeeZec = BigDecimal("0.00064"),
    etaSeconds = 140,
    expiresAt = expiresAt,
    signedMessage = "{\"intents\":[{\"intent\":\"transfer\"}]}",
    depositAddress = "0xdeposit",
    baseUnits = "441000000000000000",
    intentDeadline = expiresAt,
)
