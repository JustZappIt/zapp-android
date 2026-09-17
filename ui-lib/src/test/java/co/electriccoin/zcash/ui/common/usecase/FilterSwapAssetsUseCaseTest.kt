package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import android.content.res.Configuration
import co.electriccoin.zcash.ui.common.model.DynamicSwapAsset
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapBlockchain
import co.electriccoin.zcash.ui.common.model.ZecSwapAsset
import co.electriccoin.zcash.ui.common.repository.SwapAssetsData
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.imageRes
import io.mockk.every
import io.mockk.mockk
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The repository keeps ZEC out of the swap list because it is never a swap destination. The Send
 * screen shares that list as its asset chooser, where ZEC is the way back out of swap mode, so it
 * asks for ZEC to be put back — at the top, and regardless of any chain restriction.
 */
class FilterSwapAssetsUseCaseTest {
    // Search resolves token names through the context, which needs a locale to hand back
    private val filter =
        FilterSwapAssetsUseCase(
            mockk<Context>(relaxed = true) {
                @Suppress("DEPRECATION")
                every { resources.configuration } returns Configuration().apply { locale = Locale.US }
            }
        )

    private val assets =
        SwapAssetsData(
            data = listOf(asset("USDC", "BASE"), asset("BTC", "BTC")),
            zecAsset = zec()
        )

    @Test
    fun `ZEC is not listed unless asked for`() {
        val result = filter(assets, latestUsedAssets = null, text = "", onlyChainTicker = null)

        assertEquals(listOf("BTC", "USDC"), result.tickers())
    }

    @Test
    fun `ZEC leads the list ahead of trending and recently used tokens`() {
        val result =
            filter(assets, latestUsedAssets = null, text = "", onlyChainTicker = null, includeZec = true)

        assertEquals(listOf("ZEC", "BTC", "USDC"), result.tickers())
    }

    @Test
    fun `ZEC survives a chain restriction`() {
        val result =
            filter(assets, latestUsedAssets = null, text = "", onlyChainTicker = "base", includeZec = true)

        assertEquals(listOf("ZEC", "USDC"), result.tickers())
    }

    @Test
    fun `ZEC is searchable`() {
        val result =
            filter(assets, latestUsedAssets = null, text = "ze", onlyChainTicker = null, includeZec = true)

        assertEquals(listOf("ZEC"), result.tickers())
    }

    private fun SwapAssetsData.tickers() = data.orEmpty().map { it.tokenTicker }

    private companion object {
        fun blockchain(chain: String) =
            SwapBlockchain(chainTicker = chain, chainName = StringResource.ByString(chain), chainIcon = imageRes(chain))

        fun asset(token: String, chain: String): SwapAsset =
            DynamicSwapAsset(
                tokenTicker = token,
                tokenName = StringResource.ByString(token),
                tokenIcon = imageRes(token),
                usdPrice = null,
                assetId = "$token.$chain",
                decimals = 8,
                blockchain = blockchain(chain)
            )

        fun zec() =
            ZecSwapAsset(
                tokenTicker = "ZEC",
                tokenName = StringResource.ByString("Zcash"),
                tokenIcon = imageRes("ZEC"),
                blockchain = blockchain("ZEC"),
                usdPrice = null,
                assetId = "ZEC.ZEC",
                decimals = 8
            )
    }
}
