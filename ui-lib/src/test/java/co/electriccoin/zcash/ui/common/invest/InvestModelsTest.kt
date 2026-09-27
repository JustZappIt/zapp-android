package co.electriccoin.zcash.ui.common.invest

import co.electriccoin.zcash.ui.common.invest.model.InvestAssets
import co.electriccoin.zcash.ui.common.invest.model.TradingSchedule
import co.electriccoin.zcash.ui.common.invest.provider.InvestServerClock
import co.electriccoin.zcash.ui.common.model.near.Confidentiality
import co.electriccoin.zcash.ui.common.model.near.QuoteRequest
import co.electriccoin.zcash.ui.common.model.near.QuoteResponseDto
import co.electriccoin.zcash.ui.common.model.near.RecipientType
import co.electriccoin.zcash.ui.common.model.near.RefundType
import co.electriccoin.zcash.ui.common.model.near.SwapType
import io.ktor.serialization.kotlinx.json.DefaultJson
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

// Fixtures are real 1Click and NEAR RPC bodies, kept on one line to match what the wire carries.
@Suppress("MaxLineLength")
class InvestModelsTest {
    @Test
    fun `a swap request is encoded exactly as before confidentiality existed`() {
        val json = DefaultJson.encodeToString(QuoteRequest.serializer(), quote())

        assertFalse("confidentiality" in json, json)
        assertTrue("\"depositType\":\"ORIGIN_CHAIN\"" in json)
        assertTrue("\"recipientType\":\"DESTINATION_CHAIN\"" in json)
    }

    @Test
    fun `an Invest request names the confidential side and the confidentiality`() {
        val json =
            DefaultJson.encodeToString(
                QuoteRequest.serializer(),
                quote(recipientType = RecipientType.CONFIDENTIAL_INTENTS, confidentiality = Confidentiality.BASIC),
            )

        assertTrue("\"recipientType\":\"CONFIDENTIAL_INTENTS\"" in json, json)
        assertTrue("\"confidentiality\":\"basic\"" in json, json)
    }

    @Test
    fun `a confidential quote echo decodes its types, confidentiality and fixed fees`() {
        // Trimmed from a real dry quote, 2026-09-26 12:24:53Z (correlationId c8f4806d-…).
        val response =
            DefaultJson.decodeFromString(
                QuoteResponseDto.serializer(),
                """
                {"quote":{"amountIn":"6478278","amountInFormatted":"0.06478278","amountInUsd":"99.96",
                "minAmountIn":"6478278","amountOut":"440974000000000000","amountOutFormatted":"0.440974",
                "amountOutUsd":"99.01","minAmountOut":"436564000000000000","timeEstimate":470,
                "refundFee":"32000","withdrawFee":"0"},
                "quoteRequest":{"dry":true,"depositMode":"SIMPLE","swapType":"EXACT_INPUT","slippageTolerance":100,
                "originAsset":"nep141:zec.omft.near","depositType":"ORIGIN_CHAIN",
                "destinationAsset":"nep141:bnb-0xa9ee28c80f960b889dfbd1902055218cba016f75.omdep.near",
                "amount":"6478278","refundTo":"u1x","refundType":"ORIGIN_CHAIN","recipient":"0xabc",
                "recipientType":"CONFIDENTIAL_INTENTS","deadline":"2026-09-26T14:24:53.000Z",
                "confidentiality":"basic","referral":"zapp","quoteWaitingTimeMs":3000,
                "appFees":[{"recipient":"2238fd089f1c92b206c218cd16b8676cb98964e0d50f8ab729c6396a81e07805","fee":20}],
                "insured":false},
                "timestamp":"2026-09-26T12:24:53.931Z","correlationId":"c8f4806d-b0bb-4a93-a4a4-ee4a4109eadb"}
                """.trimIndent(),
            )

        assertEquals(RecipientType.CONFIDENTIAL_INTENTS, response.quoteRequest.recipientType)
        assertEquals(Confidentiality.BASIC, response.quoteRequest.confidentiality)
        assertEquals(BigDecimal("32000"), response.quote.refundFee)
        assertEquals(BigDecimal("0"), response.quote.withdrawFee)
        assertNull(response.quote.depositAddress)
    }

    @Test
    fun `the curated list is the ten decided on 2026-09-27, matched by asset id`() {
        val assets = InvestAssets.curated
        assertEquals(10, assets.size)
        assertEquals(assets.size, assets.map { it.assetId }.toSet().size)
        assertEquals(
            listOf("NVDA", "TSLA", "SPY", "QQQ", "GOOGL", "CRCL"),
            assets.filter { it.schedule == TradingSchedule.ALWAYS }.map { it.ticker },
        )
        assertEquals(
            listOf("AAPL", "MSFT", "AMZN", "META"),
            assets.filter { it.schedule == TradingSchedule.WEEKDAYS }.map { it.ticker },
        )
        val assetId = Regex("^nep141:bnb-0x[0-9a-f]{40}\\.omdep\\.near$")
        assets.forEach { assertTrue(assetId.matches(it.assetId), it.assetId) }
        assertEquals("NVIDIA", InvestAssets.find("nep141:bnb-0xa9ee28c80f960b889dfbd1902055218cba016f75.omdep.near")?.name)
        assertNull(InvestAssets.find("nep141:zec.omft.near"))
    }

    @Test
    fun `an unknown confidentiality in a swap echo decodes to null instead of failing the quote`() {
        val echo =
            DefaultJson.decodeFromString(
                QuoteRequest.serializer(),
                DefaultJson
                    .encodeToString(QuoteRequest.serializer(), quote())
                    .replace("\"referral\":\"zapp\"", "\"referral\":\"zapp\",\"confidentiality\":\"max\""),
            )

        assertNull(echo.confidentiality)

        val nullEcho =
            DefaultJson.decodeFromString(
                QuoteRequest.serializer(),
                DefaultJson
                    .encodeToString(QuoteRequest.serializer(), quote())
                    .replace("\"referral\":\"zapp\"", "\"referral\":\"zapp\",\"confidentiality\":null"),
            )
        assertNull(nullEcho.confidentiality)
    }

    @Test
    fun `a swap record is an Invest buy exactly when it points at a curated stock`() {
        // What the wallet's swap metadata stores for a buy: 1Click's symbol and chain.
        assertEquals("NVIDIA", InvestAssets.findBySwapTickers("NVDAon", "bsc")?.name)
        assertEquals("NVIDIA", InvestAssets.findBySwapTickers("nvdaon", "BSC")?.name)
        assertEquals(
            InvestAssets.curated.map { it.symbol },
            listOf("NVDAon", "TSLAon", "SPYon", "QQQon", "GOOGLon", "CRCLon", "AAPLon", "MSFTon", "AMZNon", "METAon"),
        )
        // Ordinary swaps, an Ondo token that isn't curated, and a curated symbol on another chain are not.
        assertNull(InvestAssets.findBySwapTickers("btc", "btc"))
        assertNull(InvestAssets.findBySwapTickers("USDC", "near"))
        assertNull(InvestAssets.findBySwapTickers("GLDon", "bsc"))
        assertNull(InvestAssets.findBySwapTickers("NVDAon", "eth"))
        assertNull(InvestAssets.findBySwapTickers("NVDA", "bsc"))
    }

    @Test
    fun `the server clock follows the Date header and ignores garbage`() {
        val clock = InvestServerClock(deviceNowMillis = { 1_790_604_000_000L })
        assertFalse(clock.isSynchronised)
        assertEquals(1_790_604_000_000L, clock.nowMillis())

        clock.observe("not a date", fromOneClick = true)
        clock.observe(null, fromOneClick = true)
        assertFalse(clock.isSynchronised)

        // A NEAR RPC host's time stands in until 1Click has answered...
        clock.observe("Mon, 28 Sep 2026 14:10:00 GMT", fromOneClick = false)
        assertEquals(1_790_604_600_000L, clock.nowMillis())

        // ...then 1Click's, the clock that judges a login, wins.
        clock.observe("Mon, 28 Sep 2026 13:58:00 GMT", fromOneClick = true)
        assertTrue(clock.isSynchronised)
        assertEquals(1_790_603_880_000L, clock.nowMillis())
        clock.observe("Mon, 28 Sep 2026 14:10:00 GMT", fromOneClick = false)
        assertEquals(1_790_603_880_000L, clock.nowMillis())
    }

    private fun quote(
        recipientType: RecipientType = RecipientType.DESTINATION_CHAIN,
        confidentiality: Confidentiality? = null,
    ) = QuoteRequest(
        dry = true,
        swapType = SwapType.EXACT_INPUT,
        slippageTolerance = 100,
        originAsset = "nep141:zec.omft.near",
        depositType = RefundType.ORIGIN_CHAIN,
        destinationAsset = "nep141:wrap.near",
        amount = BigDecimal("6478278"),
        refundTo = "u1x",
        refundType = RefundType.ORIGIN_CHAIN,
        recipient = "alice.near",
        recipientType = recipientType,
        deadline = Instant.parse("2026-09-26T14:24:53Z"),
        quoteWaitingTimeMs = 3000,
        appFees = emptyList(),
        referral = "zapp",
        confidentiality = confidentiality,
    )
}
