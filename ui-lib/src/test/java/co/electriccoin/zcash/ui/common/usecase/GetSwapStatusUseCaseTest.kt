package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.SwapDataSource
import co.electriccoin.zcash.ui.common.datasource.SwapQuoteEstimate
import co.electriccoin.zcash.ui.common.model.DynamicSwapAsset
import co.electriccoin.zcash.ui.common.model.SimpleSwapAsset
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapBlockchain
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.SwapQuoteStatus
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.SwapAssetsData
import co.electriccoin.zcash.ui.common.repository.SwapQuoteData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionMetadata
import co.electriccoin.zcash.ui.common.repository.TransactionSwapMetadata
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.imageRes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The status poll is only trustworthy when stored metadata is present to check the returned assets
 * against, so a missing entry fails closed: an error state instead of an unverified poll. Unusable
 * supported assets fail closed the same way. The asset comparison itself is covered by
 * `NearSwapQuoteValidationTest`.
 */
class GetSwapStatusUseCaseTest {
    @Test
    fun surfacesErrorAndSkipsStatusLookupWhenNoStoredMetadata() =
        runTest {
            val metadataRepository = FakeMetadataRepository(swapMetadata = null)

            val result =
                GetSwapStatusUseCase(
                    swapDataSource = ThrowingSwapDataSource(),
                    metadataRepository = metadataRepository,
                    swapRepository = FakeSwapRepository()
                ).invoke(DEPOSIT_ADDRESS)

            assertFalse(result.isLoading)
            assertIs<IllegalStateException>(result.error)
            assertNull(result.status)
            assertEquals(0, metadataRepository.updateSwapCallCount)
        }

    @Test
    fun surfacesErrorAndSkipsStatusLookupWhenRefreshedAssetsHaveNoZec() =
        runTest {
            val metadataRepository = FakeMetadataRepository(swapMetadata = null)

            val result =
                GetSwapStatusUseCase(
                    swapDataSource = ThrowingSwapDataSource(),
                    metadataRepository = metadataRepository,
                    swapRepository =
                        FakeSwapRepository(
                            SwapAssetsData(
                                data = listOf(ASSET),
                                zecAsset = null,
                                isLoading = false,
                                error = null
                            )
                        )
                ).invoke(DEPOSIT_ADDRESS)

            assertFalse(result.isLoading)
            assertIs<IllegalStateException>(result.error)
            assertNull(result.status)
            assertEquals(0, metadataRepository.updateSwapCallCount)
        }
}

private const val DEPOSIT_ADDRESS = "deposit-address"

private val ASSET: SwapAsset =
    DynamicSwapAsset(
        tokenTicker = "btc",
        tokenName = StringResource.ByString("btc"),
        tokenIcon = imageRes("btc"),
        usdPrice = null,
        assetId = "btc.btc",
        decimals = 8,
        blockchain =
            SwapBlockchain(
                chainTicker = "btc",
                chainName = StringResource.ByString("btc"),
                chainIcon = imageRes("btc")
            )
    )

private class ThrowingSwapDataSource : SwapDataSource {
    override suspend fun checkSwapStatus(
        depositAddress: String,
        supportedTokens: List<SwapAsset>
    ): SwapQuoteStatus = throw AssertionError("status must not be polled without verified inputs")

    override suspend fun getSupportedTokens(): List<SwapAsset> = throw AssertionError("unused")

    override suspend fun requestQuote(
        swapMode: SwapMode,
        flexInput: Boolean,
        amount: BigDecimal,
        refundAddress: String,
        originAsset: SwapAsset,
        destinationAddress: String,
        destinationAsset: SwapAsset,
        slippage: BigDecimal,
        affiliateAddress: String
    ): SwapQuote = throw AssertionError("unused")

    override suspend fun requestQuoteEstimate(
        swapMode: SwapMode,
        flexInput: Boolean,
        amount: BigDecimal,
        refundAddress: String,
        originAsset: SwapAsset,
        destinationAddress: String,
        destinationAsset: SwapAsset,
        slippage: BigDecimal,
        affiliateAddress: String
    ): SwapQuoteEstimate = throw AssertionError("unused")

    override suspend fun submitDepositTransaction(txHash: String, depositAddress: String) = Unit
}

private class FakeMetadataRepository(
    private val swapMetadata: TransactionSwapMetadata?
) : MetadataRepository {
    var updateSwapCallCount = 0
        private set

    override suspend fun getSwapMetadata(depositAddress: String): TransactionSwapMetadata? = swapMetadata

    override fun updateSwap(
        depositAddress: String,
        amountOutFormatted: BigDecimal,
        status: SwapStatus,
        mode: SwapMode,
        origin: SwapAsset,
        destination: SwapAsset
    ) {
        updateSwapCallCount++
    }

    override fun flipTxBookmark(txId: String) = Unit

    override fun createOrUpdateTxNote(txId: String, note: String) = Unit

    override fun deleteTxNote(txId: String) = Unit

    override fun markTxMemoAsRead(txId: String) = Unit

    override fun markTxAsSwap(
        depositAddress: String,
        provider: String,
        origin: SwapAsset,
        destination: SwapAsset,
        totalFees: Zatoshi,
        totalFeesUsd: BigDecimal,
        amountOutFormatted: BigDecimal,
        mode: SwapMode,
        status: SwapStatus
    ) = Unit

    override fun addSwapAssetToHistory(tokenTicker: String, chainTicker: String) = Unit

    override fun observeTransactionMetadata(transaction: Transaction): Flow<TransactionMetadata> = emptyFlow()

    override fun observeSwapMetadata(): Flow<List<TransactionSwapMetadata>?> = emptyFlow()

    override fun observeLastUsedAssetHistory(): Flow<Set<SimpleSwapAsset>?> = emptyFlow()

    override fun delete() = Unit
}

private class FakeSwapRepository(
    assetsData: SwapAssetsData =
        SwapAssetsData(
            data = listOf(ASSET),
            zecAsset = ASSET,
            isLoading = false
        )
) : SwapRepository {
    override val assets = MutableStateFlow(assetsData)

    override val selectedAsset = MutableStateFlow<SwapAsset?>(null)

    override val slippage = MutableStateFlow(BigDecimal.ZERO)

    override val quote = MutableStateFlow<SwapQuoteData?>(null)

    override fun select(asset: SwapAsset?) = Unit

    override fun setSlippage(amount: BigDecimal) = Unit

    override fun requestRefreshAssets() = Unit

    override suspend fun requestRefreshAssetsOnce() = Unit

    override fun requestExactInputQuote(amount: BigDecimal, address: String, refundAddress: String) = Unit

    override fun requestExactOutputQuote(amount: BigDecimal, address: String, refundAddress: String) = Unit

    override fun requestExactInputIntoZec(
        amount: BigDecimal,
        refundAddress: String,
        destinationAddress: String
    ) = Unit

    override fun clear() = Unit

    override fun clearQuote() = Unit
}
