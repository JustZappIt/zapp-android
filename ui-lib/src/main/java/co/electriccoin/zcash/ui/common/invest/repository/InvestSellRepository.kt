package co.electriccoin.zcash.ui.common.invest.repository

import co.electriccoin.zcash.ui.common.invest.model.InvestAsset
import co.electriccoin.zcash.ui.common.invest.model.PreparedSell
import co.electriccoin.zcash.ui.common.invest.model.SellAmount
import co.electriccoin.zcash.ui.common.invest.model.SellEstimate
import co.electriccoin.zcash.ui.common.invest.model.SellProgress
import kotlinx.coroutines.flow.Flow

/**
 * Selling a holding back to ZEC: a 1Click quote whose deposit comes from the private account
 * (`depositType CONFIDENTIAL_INTENTS`), paid by an intent the private-account key signs.
 *
 * UNVERIFIED: no confidential sell has been observed to succeed end to end (near/intents#356), and the
 * generated intent's shape is assumed until the founder's runbook step C0 captures one. Anything unexpected
 * is refused rather than signed.
 */
interface InvestSellRepository {
    /** Sells signed but not yet final, including ones from before the app was last closed. */
    val pendingSells: Flow<List<String>>

    suspend fun estimateSell(
        asset: InvestAsset,
        amount: SellAmount,
    ): SellEstimate

    /**
     * A live quote plus its generated intent, checked before anything is shown. Throws
     * [co.electriccoin.zcash.ui.common.invest.model.SellIntentRefusedException] if the intent isn't the
     * reviewed transfer.
     */
    suspend fun prepareSell(
        asset: InvestAsset,
        amount: SellAmount,
    ): PreparedSell

    /** Asks for biometrics, signs on the device and submits. Returns the deposit address that identifies it. */
    suspend fun executeSell(prepared: PreparedSell): String

    fun observeSell(depositAddress: String): Flow<SellProgress>

    /** Stops listing a sell that needs attention once it has gone to support. */
    suspend fun dismissSell(depositAddress: String)

    /** Forgets everything cached for the current wallet; called when the wallet is reset. */
    fun clearWalletData()
}
