package co.electriccoin.zcash.ui.common.repository

import co.electriccoin.zcash.ui.common.datasource.SwapDataSource
import co.electriccoin.zcash.ui.common.model.DynamicSwapAsset
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapBlockchain
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapMode.EXACT_INPUT
import co.electriccoin.zcash.ui.common.model.SwapMode.EXACT_OUTPUT
import co.electriccoin.zcash.ui.common.model.SwapMode.FLEX_INPUT
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.SwapQuoteStatus
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.imageRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.math.BigDecimal
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * Unit tests for the swap quote request paths.
 *
 * Every quote request publishes [SwapQuoteData.Loading] first, and RequestSwapQuoteUseCase suspends
 * until the quote leaves that state. A request that returns early without publishing a terminal
 * state therefore strands the caller forever, so each bail-out below has to end in an error.
 *
 * The repository's coroutine scope is injected with an [UnconfinedTestDispatcher] so the
 * fire-and-forget refresh/quote jobs run eagerly and deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwapRepositoryImplTest {
    private val testScope = CoroutineScope(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        testScope.cancel()
    }

    // region missing assets must end in a terminal error rather than a permanent Loading

    @Test
    fun `requestExactInputIntoZec reports an error when no asset is selected`() {
        val repository = repository(FailingSwapDataSource())

        repository.requestExactInputIntoZec(
            amount = BigDecimal.ONE,
            refundAddress = REFUND_ADDRESS,
            destinationAddress = DESTINATION_ADDRESS
        )

        val error = assertIs<SwapQuoteData.Error>(repository.quote.value)
        assertEquals(FLEX_INPUT, error.mode)
        assertIs<SwapAssetNotSelectedException>(error.exception)
    }

    @Test
    fun `requestExactInputIntoZec reports an error when the ZEC asset is unavailable`() {
        val repository = repository(FailingSwapDataSource())
        repository.select(asset(token = "USDC", chain = "ETH"))

        repository.requestExactInputIntoZec(
            amount = BigDecimal.ONE,
            refundAddress = REFUND_ADDRESS,
            destinationAddress = DESTINATION_ADDRESS
        )

        val error = assertIs<SwapQuoteData.Error>(repository.quote.value)
        assertEquals(FLEX_INPUT, error.mode)
        assertIs<SwapAssetsUnavailableException>(error.exception)
    }

    @Test
    fun `requestExactInputQuote reports an error when the ZEC asset is unavailable`() {
        val repository = repository(FailingSwapDataSource())
        repository.select(asset(token = "USDC", chain = "ETH"))

        repository.requestExactInputQuote(
            amount = BigDecimal.ONE,
            address = DESTINATION_ADDRESS,
            refundAddress = REFUND_ADDRESS
        )

        val error = assertIs<SwapQuoteData.Error>(repository.quote.value)
        assertEquals(EXACT_INPUT, error.mode)
        assertIs<SwapAssetsUnavailableException>(error.exception)
    }

    @Test
    fun `requestExactOutputQuote reports an error when the ZEC asset is unavailable`() {
        val repository = repository(FailingSwapDataSource())
        repository.select(asset(token = "USDC", chain = "ETH"))

        repository.requestExactOutputQuote(
            amount = BigDecimal.ONE,
            address = DESTINATION_ADDRESS,
            refundAddress = REFUND_ADDRESS
        )

        val error = assertIs<SwapQuoteData.Error>(repository.quote.value)
        assertEquals(EXACT_OUTPUT, error.mode)
        assertIs<SwapAssetsUnavailableException>(error.exception)
    }

    @Test
    fun `requestExactInputQuote reports an error when no asset is selected`() {
        val repository = repository(FailingSwapDataSource())
        repository.withZecAsset()

        repository.requestExactInputQuote(
            amount = BigDecimal.ONE,
            address = DESTINATION_ADDRESS,
            refundAddress = REFUND_ADDRESS
        )

        val error = assertIs<SwapQuoteData.Error>(repository.quote.value)
        assertEquals(EXACT_INPUT, error.mode)
        assertIs<SwapAssetNotSelectedException>(error.exception)
    }

    @Test
    fun `requestExactOutputQuote reports an error when no asset is selected`() {
        val repository = repository(FailingSwapDataSource())
        repository.withZecAsset()

        repository.requestExactOutputQuote(
            amount = BigDecimal.ONE,
            address = DESTINATION_ADDRESS,
            refundAddress = REFUND_ADDRESS
        )

        val error = assertIs<SwapQuoteData.Error>(repository.quote.value)
        assertEquals(EXACT_OUTPUT, error.mode)
        assertIs<SwapAssetNotSelectedException>(error.exception)
    }

    // endregion

    // region a failed request reports the mode it was made with, not a hardcoded one

    @Test
    fun `requestExactInputQuote reports the exact input mode when the request fails`() {
        val failure = IllegalStateException("quote unavailable")
        val repository = repository(FailingSwapDataSource(failure))
        repository.withZecAsset()
        repository.select(asset(token = "USDC", chain = "ETH"))

        repository.requestExactInputQuote(
            amount = BigDecimal.ONE,
            address = DESTINATION_ADDRESS,
            refundAddress = REFUND_ADDRESS
        )

        val error = assertIs<SwapQuoteData.Error>(repository.quote.value)
        // The error must carry the request's own mode, not a hardcoded one.
        assertEquals(EXACT_INPUT, error.mode)
        assertSame(failure, error.exception)
    }

    @Test
    fun `requestExactOutputQuote reports the exact output mode when the request fails`() {
        val failure = IllegalStateException("quote unavailable")
        val repository = repository(FailingSwapDataSource(failure))
        repository.withZecAsset()
        repository.select(asset(token = "USDC", chain = "ETH"))

        repository.requestExactOutputQuote(
            amount = BigDecimal.ONE,
            address = DESTINATION_ADDRESS,
            refundAddress = REFUND_ADDRESS
        )

        val error = assertIs<SwapQuoteData.Error>(repository.quote.value)
        assertEquals(EXACT_OUTPUT, error.mode)
        assertSame(failure, error.exception)
    }

    @Test
    fun `requestExactInputIntoZec reports the flex input mode when the request fails`() {
        val failure = IllegalStateException("quote unavailable")
        val repository = repository(FailingSwapDataSource(failure))
        repository.withZecAsset()
        repository.select(asset(token = "USDC", chain = "ETH"))

        repository.requestExactInputIntoZec(
            amount = BigDecimal.ONE,
            refundAddress = REFUND_ADDRESS,
            destinationAddress = DESTINATION_ADDRESS
        )

        val error = assertIs<SwapQuoteData.Error>(repository.quote.value)
        assertEquals(FLEX_INPUT, error.mode)
        assertSame(failure, error.exception)
    }

    // endregion

    /** Builds the repository with its background scope swapped for the eager test scope. */
    private fun repository(dataSource: SwapDataSource): SwapRepositoryImpl =
        SwapRepositoryImpl(dataSource).apply { scope = testScope }

    private fun SwapRepositoryImpl.withZecAsset() =
        assets.update { it.copy(zecAsset = asset(token = "ZEC", chain = "ZEC")) }

    private fun blockchain(chain: String) =
        SwapBlockchain(chainTicker = chain, chainName = StringResource.ByString(chain), chainIcon = imageRes(chain))

    private fun asset(token: String, chain: String) =
        DynamicSwapAsset(
            tokenTicker = token,
            tokenName = StringResource.ByString(token),
            tokenIcon = imageRes(token),
            usdPrice = null,
            assetId = "$token.$chain",
            decimals = 8,
            blockchain = blockchain(chain)
        )

    private companion object {
        const val REFUND_ADDRESS = "refund-address"
        const val DESTINATION_ADDRESS = "destination-address"
    }
}

private class FailingSwapDataSource(
    private val failure: Exception = IllegalStateException("not stubbed")
) : SwapDataSource {
    override suspend fun getSupportedTokens(): List<SwapAsset> = throw failure

    @Suppress("LongParameterList")
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
    ): SwapQuote = throw failure

    override suspend fun submitDepositTransaction(txHash: String, depositAddress: String): Unit = throw failure

    override suspend fun checkSwapStatus(
        depositAddress: String,
        supportedTokens: List<SwapAsset>
    ): SwapQuoteStatus = throw failure
}
