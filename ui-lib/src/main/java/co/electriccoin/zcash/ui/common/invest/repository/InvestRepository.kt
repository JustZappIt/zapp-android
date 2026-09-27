package co.electriccoin.zcash.ui.common.invest.repository

import co.electriccoin.zcash.ui.common.invest.model.BuyEstimate
import co.electriccoin.zcash.ui.common.invest.model.BuyProgress
import co.electriccoin.zcash.ui.common.invest.model.Holdings
import co.electriccoin.zcash.ui.common.invest.model.InvestAsset
import co.electriccoin.zcash.ui.common.invest.model.InvestMarket
import co.electriccoin.zcash.ui.common.invest.model.PreparedBuy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.math.BigDecimal

/**
 * Everything the Invest screens need, in their terms. Network failures surface as
 * [co.electriccoin.zcash.ui.common.invest.provider.InvestApiException] subclasses from the suspend calls;
 * the flows keep their last value.
 */
interface InvestRepository {
    /** The curated stocks with prices; null until the first load. */
    val market: StateFlow<InvestMarket?>

    /** The private account's positions; null until the first successful load. */
    val holdings: StateFlow<Holdings?>

    /** Buys that were sent but haven't finished, including ones from before the app was last closed. */
    val pendingBuys: Flow<List<String>>

    suspend fun refreshMarket()

    suspend fun refreshHoldings()

    /** A dry quote for [usdAmount] of [asset]; the screen calls this after typing stops. */
    suspend fun estimateBuy(
        asset: InvestAsset,
        usdAmount: BigDecimal,
    ): BuyEstimate

    /** A live quote for the review sheet, checked against the request before anything is shown. */
    suspend fun prepareBuy(
        asset: InvestAsset,
        usdAmount: BigDecimal,
    ): PreparedBuy

    /**
     * Records the buy, then sends the ZEC from the shielded balance (the existing biometric send flow).
     * Returns the deposit address that identifies the buy. Throws if the user cancels authentication.
     */
    suspend fun executeBuy(prepared: PreparedBuy): String

    /** Live progress of one buy, until it is final. */
    fun observeBuy(depositAddress: String): Flow<BuyProgress>

    companion object {
        /** The in-app minimum (decided 2026-09-27): nothing below about $30 quoted on 2026-09-25/26. */
        val MINIMUM_USD: BigDecimal = BigDecimal(40)
    }
}
