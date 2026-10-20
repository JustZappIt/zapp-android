package co.electriccoin.zcash.ui.common.invest.repository

import co.electriccoin.zcash.ui.common.model.SwapAsset

/** The curated stocks as swap assets, so a buy's swap record resolves. */
interface InvestSwapAssetSource {
    suspend fun investSwapAssets(): List<SwapAsset>
}
