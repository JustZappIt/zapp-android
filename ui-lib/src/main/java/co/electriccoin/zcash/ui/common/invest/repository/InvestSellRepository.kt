package co.electriccoin.zcash.ui.common.invest.repository

import co.electriccoin.zcash.ui.common.invest.model.InvestAsset
import co.electriccoin.zcash.ui.common.invest.model.PreparedSell
import co.electriccoin.zcash.ui.common.invest.model.SellAmount
import co.electriccoin.zcash.ui.common.invest.model.SellEstimate
import co.electriccoin.zcash.ui.common.invest.model.SellProgress
import kotlinx.coroutines.flow.Flow

/** Selling a holding back to ZEC, paid by an intent the private-account key signs. */
interface InvestSellRepository {
    suspend fun estimateSell(
        asset: InvestAsset,
        amount: SellAmount,
    ): SellEstimate

    /** Throws [co.electriccoin.zcash.ui.common.invest.model.SellIntentRefusedException] for an unexpected intent. */
    suspend fun prepareSell(
        asset: InvestAsset,
        amount: SellAmount,
    ): PreparedSell

    /** Asks for biometrics, signs and submits; returns the deposit address. */
    suspend fun executeSell(prepared: PreparedSell): String

    fun observeSell(depositAddress: String): Flow<SellProgress>

    /** Stops listing a sale that needs attention. */
    suspend fun dismissSell(depositAddress: String)

    fun clearWalletData()
}
