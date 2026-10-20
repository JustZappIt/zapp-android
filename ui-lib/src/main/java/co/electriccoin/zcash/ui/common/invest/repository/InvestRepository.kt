package co.electriccoin.zcash.ui.common.invest.repository

import co.electriccoin.zcash.ui.common.invest.model.BuyEstimate
import co.electriccoin.zcash.ui.common.invest.model.BuyProgress
import co.electriccoin.zcash.ui.common.invest.model.Holdings
import co.electriccoin.zcash.ui.common.invest.model.InvestAsset
import co.electriccoin.zcash.ui.common.invest.model.InvestMarket
import co.electriccoin.zcash.ui.common.invest.model.PendingTrade
import co.electriccoin.zcash.ui.common.invest.model.PreparedBuy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.math.BigDecimal

/** Invest's market, holdings and buys. Failed 1Click calls throw an `InvestApiException`. */
interface InvestRepository {
    /** Null until the first load. */
    val market: StateFlow<InvestMarket?>

    /** Null until the first successful load. */
    val holdings: StateFlow<Holdings?>

    /** Every buy and sale not yet final; null when the records can't be read, which stops all trading. */
    val pendingTrades: Flow<List<PendingTrade>?>

    suspend fun refreshMarket()

    suspend fun refreshHoldings()

    suspend fun estimateBuy(
        asset: InvestAsset,
        usdAmount: BigDecimal,
    ): BuyEstimate

    suspend fun prepareBuy(
        asset: InvestAsset,
        usdAmount: BigDecimal,
    ): PreparedBuy

    /** Records the buy and sends its ZEC after the biometric prompt; returns the deposit address. */
    suspend fun executeBuy(prepared: PreparedBuy): String

    /** Only the phone's own account: a Keystone user's stocks would be held by a key on this phone. */
    suspend fun isAccountSupported(): Boolean

    fun observeBuy(depositAddress: String): Flow<BuyProgress>

    /** Stops listing a buy that needs attention; 1Click still settles or refunds it. */
    suspend fun dismissBuy(depositAddress: String)

    fun clearWalletData()

    companion object {
        val MINIMUM_USD: BigDecimal = BigDecimal(40)
    }
}
