package co.electriccoin.zcash.ui.common.datasource

import co.electriccoin.zcash.ui.common.model.DynamicSwapAsset
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapBlockchain
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.near.ErrorDto
import co.electriccoin.zcash.ui.common.model.near.QuoteDetails
import co.electriccoin.zcash.ui.common.model.near.QuoteRequest
import co.electriccoin.zcash.ui.common.model.near.QuoteResponseDto
import co.electriccoin.zcash.ui.common.model.near.SwapAmountInconsistencyException
import co.electriccoin.zcash.ui.common.model.near.SwapType
import co.electriccoin.zcash.ui.common.provider.NearApiProvider
import co.electriccoin.zcash.ui.common.provider.ResponseWithNearErrorException
import co.electriccoin.zcash.ui.common.provider.SwapAssetProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.imageRes
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Locks the split between the two quote calls. [SwapDataSource.requestQuote] opens a swap — 1Click
 * reserves a deposit address and then tracks it as awaiting funds — while
 * [SwapDataSource.requestQuoteEstimate] only prices one. The `dry` flag is the entire difference, and
 * getting it wrong the reserving way is invisible in the app: it shows up only as abandoned records on
 * the provider's side. So it is asserted explicitly on both.
 */
class NearSwapDataSourceImplTest {
    private val nearApiProvider = mockk<NearApiProvider>()
    private val swapAssetProvider = mockk<SwapAssetProvider>(relaxed = true)
    private val synchronizerProvider = mockk<SynchronizerProvider>(relaxed = true)
    private val dataSource = NearSwapDataSourceImpl(nearApiProvider, swapAssetProvider, synchronizerProvider)

    private val origin = asset(assetId = ORIGIN_ID, token = "TKA", chain = "chaina", decimals = 8)
    private val destination = asset(assetId = DEST_ID, token = "TKB", chain = "chainb", decimals = 6)

    @Test
    fun requestQuoteEstimateSendsDryRequest() {
        runBlocking {
            val request = slot<QuoteRequest>()
            coEvery { nearApiProvider.requestQuote(capture(request)) } returns quoteResponse()

            estimate()

            assertTrue(request.captured.dry)
        }
    }

    @Test
    fun requestQuoteSendsExecutableRequest() {
        runBlocking {
            val request = slot<QuoteRequest>()
            coEvery { nearApiProvider.requestQuote(capture(request)) } throws nearError("No quotes found")

            assertFailsWith<QuoteLowAmountException> { quote() }

            assertFalse(request.captured.dry)
        }
    }

    @Test
    fun requestQuoteEstimateReturnsThePreviewFigures() {
        runBlocking {
            coEvery { nearApiProvider.requestQuote(any()) } returns quoteResponse(timeEstimate = 42)

            val estimate = estimate()

            assertEquals(0, BigDecimal("100000000").compareTo(estimate.amountIn))
            assertEquals(42, estimate.estimatedDurationSeconds)
        }
    }

    /** The preview must show the slippage the client asked for, not whatever the server echoes back. */
    @Test
    fun requestQuoteEstimateReportsClientSlippageNotServerEcho() {
        runBlocking {
            coEvery { nearApiProvider.requestQuote(any()) } returns quoteResponse(slippageBps = 10000)

            val estimate = estimate(slippage = BigDecimal("2"))

            assertEquals(0, BigDecimal("2").compareTo(estimate.slippage))
        }
    }

    @Test
    fun requestQuoteEstimateRejectsSwapTypeMismatch() {
        runBlocking {
            coEvery { nearApiProvider.requestQuote(any()) } returns quoteResponse(swapType = SwapType.EXACT_OUTPUT)

            assertFailsWith<IllegalArgumentException> { estimate() }
        }
    }

    /** A preview is priced off the same numbers the executable quote is, so it fails on the same check. */
    @Test
    fun requestQuoteEstimateRejectsInconsistentAmountIn() {
        runBlocking {
            // amountInFormatted=1 at the origin's 8 decimals expects 100_000_000; the server says 999.
            coEvery { nearApiProvider.requestQuote(any()) } returns quoteResponse(amountIn = BigDecimal("999"))

            assertFailsWith<SwapAmountInconsistencyException> { estimate() }
        }
    }

    @Test
    fun requestQuoteEstimateMapsLowAmountErrors() {
        runBlocking {
            coEvery { nearApiProvider.requestQuote(any()) } throws
                nearError("Amount is too low for bridge, try at least 1000")

            val exception = assertFailsWith<QuoteLowAmountException> { estimate() }

            assertEquals(origin, exception.asset)
            assertEquals(0, BigDecimal("0.00001").compareTo(exception.amountFormatted)) // 1000 / 10^8
        }
    }

    /**
     * Only a dry quote legitimately has no deposit address. If one came back on the executable path the
     * ZEC send would have nowhere to go, so it is rejected rather than resolved to an empty address.
     */
    @Test
    fun requestQuoteRejectsExecutableQuoteWithoutDepositAddress() {
        runBlocking {
            listOf(null, "", "   ").forEach { address ->
                coEvery { nearApiProvider.requestQuote(any()) } returns quoteResponse(depositAddress = address)

                assertFailsWith<IllegalArgumentException>("deposit address <$address> must be rejected") {
                    quote()
                }
            }
        }
    }

    private suspend fun estimate(slippage: BigDecimal = BigDecimal("2")): SwapQuoteEstimate =
        dataSource.requestQuoteEstimate(
            swapMode = SwapMode.EXACT_INPUT,
            flexInput = false,
            amount = BigDecimal("1"),
            refundAddress = REFUND_ADDRESS,
            originAsset = origin,
            destinationAddress = RECIPIENT_ADDRESS,
            destinationAsset = destination,
            slippage = slippage,
            affiliateAddress = "affiliate"
        )

    private suspend fun quote() =
        dataSource.requestQuote(
            swapMode = SwapMode.EXACT_INPUT,
            flexInput = false,
            amount = BigDecimal("1"),
            refundAddress = REFUND_ADDRESS,
            originAsset = origin,
            destinationAddress = RECIPIENT_ADDRESS,
            destinationAsset = destination,
            slippage = BigDecimal("2"),
            affiliateAddress = "affiliate"
        )

    private fun quoteResponse(
        swapType: SwapType = SwapType.EXACT_INPUT,
        slippageBps: Int = 200,
        depositAddress: String? = DEPOSIT_ADDRESS,
        amountIn: BigDecimal = BigDecimal("100000000"),
        timeEstimate: Int? = null
    ): QuoteResponseDto =
        QuoteResponseDto(
            timestamp = EPOCH,
            quoteRequest =
                QuoteRequest(
                    dry = false,
                    swapType = swapType,
                    slippageTolerance = slippageBps,
                    originAsset = ORIGIN_ID,
                    destinationAsset = DEST_ID,
                    amount = amountIn,
                    refundTo = REFUND_ADDRESS,
                    recipient = RECIPIENT_ADDRESS,
                    deadline = EPOCH,
                    appFees = emptyList()
                ),
            quote =
                QuoteDetails(
                    depositAddress = depositAddress,
                    amountIn = amountIn,
                    amountInFormatted = BigDecimal("1"),
                    amountInUsd = BigDecimal("10"),
                    minAmountIn = amountIn,
                    amountOut = BigDecimal("2000000"),
                    amountOutFormatted = BigDecimal("2"),
                    amountOutUsd = BigDecimal("10"),
                    minAmountOut = BigDecimal("2000000"),
                    deadline = EPOCH,
                    timeEstimate = timeEstimate
                )
        )

    private fun nearError(message: String): ResponseWithNearErrorException =
        mockk(relaxed = true) {
            every { error } returns ErrorDto(message = message, timestamp = "", path = "")
        }

    private fun asset(assetId: String, token: String, chain: String, decimals: Int): SwapAsset =
        DynamicSwapAsset(
            tokenTicker = token,
            tokenName = StringResource.ByString(token),
            tokenIcon = imageRes(token),
            usdPrice = null,
            assetId = assetId,
            decimals = decimals,
            blockchain =
                SwapBlockchain(
                    chainTicker = chain,
                    chainName = StringResource.ByString(chain),
                    chainIcon = imageRes(chain)
                )
        )

    private companion object {
        const val ORIGIN_ID = "tka.chaina"
        const val DEST_ID = "tkb.chainb"
        const val DEPOSIT_ADDRESS = "deposit-address"
        const val RECIPIENT_ADDRESS = "recipient-address"
        const val REFUND_ADDRESS = "refund-address"
        val EPOCH: Instant = Instant.fromEpochSeconds(0)
    }
}
