package co.electriccoin.zcash.ui.common.model.near

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Pins the 1Click quote payloads the app has to parse. The dry case is the load-bearing one: the whole
 * preview path rests on a response that omits `depositAddress` still deserializing, and every other field
 * of [QuoteDetails] is mandatory, so a provider that dropped one would break previews silently.
 *
 * Parsed with a strict [Json] so the `@JsonIgnoreUnknownKeys` on the DTOs is what absorbs the unmapped
 * `signature` field, rather than a lenient parser hiding whether the annotation is still there.
 */
class QuoteResponseDtoTest {
    @Test
    fun dryQuoteParsesWithoutDepositAddress() {
        val response = Json.decodeFromString<QuoteResponseDto>(DRY_QUOTE_JSON)

        assertNull(response.quote.depositAddress)
        assertEquals(0, BigDecimal("100000000").compareTo(response.quote.amountIn))
        assertEquals(0, BigDecimal("1").compareTo(response.quote.amountInFormatted))
        assertEquals(120, response.quote.timeEstimate)
    }

    @Test
    fun executableQuoteParsesDepositAddress() {
        val response = Json.decodeFromString<QuoteResponseDto>(EXECUTABLE_QUOTE_JSON)

        assertEquals("deposit-address", response.quote.depositAddress)
    }

    @Test
    fun quoteWithoutTimeEstimateParses() {
        val response = Json.decodeFromString<QuoteResponseDto>(DRY_QUOTE_JSON.without("timeEstimate"))

        assertNull(response.quote.timeEstimate)
    }

    /**
     * Everything but `depositAddress` and `timeEstimate` stays mandatory. If 1Click ever drops one of
     * these from a dry response it fails here, rather than as a preview that silently never loads.
     */
    @Test
    fun dryQuoteStillRequiresTheRestOfTheQuote() {
        listOf(
            "amountIn",
            "amountInFormatted",
            "amountInUsd",
            "minAmountIn",
            "amountOut",
            "amountOutFormatted",
            "amountOutUsd",
            "minAmountOut",
            "deadline"
        ).forEach { field ->
            assertFailsWith<SerializationException>("a dry quote without $field must not parse") {
                Json.decodeFromString<QuoteResponseDto>(DRY_QUOTE_JSON.without(field))
            }
        }
    }

    /** Drops a whole `"name": ...` line, un-dangling the comma when the dropped field closed its object. */
    private fun String.without(name: String): String {
        val kept = lines().filterNot { it.trimStart().startsWith("\"$name\"") }
        return kept
            .mapIndexed { index, line ->
                if (kept.getOrNull(index + 1)?.trimStart()?.startsWith("}") == true) {
                    line.trimEnd().removeSuffix(",")
                } else {
                    line
                }
            }.joinToString("\n")
    }

    private companion object {
        // Shape of a 1Click /v0/quote response. `signature` is deliberately present and unmapped.
        val DRY_QUOTE_JSON =
            """
            {
              "timestamp": "2026-08-30T00:00:00Z",
              "signature": "ed25519:signature",
              "quoteRequest": {
                "dry": true,
                "swapType": "EXACT_INPUT",
                "slippageTolerance": 100,
                "originAsset": "tka.chaina",
                "destinationAsset": "tkb.chainb",
                "amount": "100000000",
                "refundTo": "refund-address",
                "recipient": "recipient-address",
                "deadline": "2026-08-30T02:00:00Z",
                "appFees": []
              },
              "quote": {
                "amountIn": "100000000",
                "amountInFormatted": "1",
                "amountInUsd": "10",
                "minAmountIn": "100000000",
                "amountOut": "2000000",
                "amountOutFormatted": "2",
                "amountOutUsd": "10",
                "minAmountOut": "1980000",
                "deadline": "2026-08-30T02:00:00Z",
                "timeEstimate": 120
              }
            }
            """.trimIndent()

        val EXECUTABLE_QUOTE_JSON =
            DRY_QUOTE_JSON
                .replace("\"dry\": true", "\"dry\": false")
                .replace(
                    "\"quote\": {",
                    "\"quote\": {\n    \"depositAddress\": \"deposit-address\","
                )
    }
}
