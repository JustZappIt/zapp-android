package co.electriccoin.zcash.ui.common.invest.repository

import co.electriccoin.zcash.ui.common.model.SwapAsset

/** Swap assets for the curated stocks, for code that resolves a buy's swap record (status, activity). */
interface InvestSwapAssetSource {
    suspend fun investSwapAssets(): List<SwapAsset>
}
