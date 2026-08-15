package co.electriccoin.zcash.ui.common.pricing.datasource

import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.common.pricing.model.PriceDateRange
import co.electriccoin.zcash.ui.common.pricing.model.PricingFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@Suppress("MaxLineLength")
class PricingEngineParsingTest {
    @Test
    fun complete_response_is_parsed() {
        val parsed = parsePage(response(points = point(DAY_1, 25.5)), RANGE, FiatCurrency.USD) as ParsedPage.Valid

        assertTrue(parsed.complete)
        assertEquals(
            BigDecimal("25.5"),
            parsed.points
                .single()
                .second.fiatPerZec
        )
        assertEquals(LocalDate.parse("2026-08-01"), parsed.metadata.availableFrom)
    }

    @Test
    fun empty_complete_response_is_valid() {
        val parsed = parsePage(response(points = ""), RANGE, FiatCurrency.USD) as ParsedPage.Valid

        assertTrue(parsed.complete)
        assertTrue(parsed.points.isEmpty())
    }

    @Test
    fun incomplete_page_requires_advancing_cursor() {
        val missing = parsePage(response(points = point(DAY_1, 25.0), complete = false), RANGE, FiatCurrency.USD)
        val repeated =
            parsePage(
                response(points = point(DAY_1, 25.0), complete = false, cursor = DAY_1),
                RANGE,
                FiatCurrency.USD,
            ) as ParsedPage.Valid

        assertTrue(missing is ParsedPage.Valid && missing.nextCursor == null)
        assertEquals(Instant.parse(DAY_1), repeated.nextCursor)
        // Cursor advancement is enforced by the paginator after page parsing, before another request.
        assertTrue(!requireNotNull(repeated.nextCursor).isAfter(repeated.points.last().first))
    }

    @Test
    fun structured_series_unavailable_is_distinct_from_other_stable_errors() {
        val series = parsePricingError(404, error("SERIES_UNAVAILABLE"))
        assertEquals(PricingFailure.SeriesUnavailable, series)

        mapOf(
            "INVALID_QUERY" to 400,
            "METHOD_NOT_ALLOWED" to 405,
            "ROUTE_NOT_FOUND" to 404,
            "INTERNAL_ERROR" to 500,
        ).forEach { (code, status) ->
            assertEquals(PricingFailure.Http(status), parsePricingError(status, error(code)))
        }
    }

    @Test
    fun malformed_metadata_is_rejected() {
        val wrongSeries = response(points = point(DAY_1, 25.0)).replace("\"ZEC\"", "\"BTC\"")
        val wrongBounds = response(points = point(DAY_1, 25.0)).replace(RANGE_FROM, "2026-07-31T00:00:00Z")
        val wrongAsOf = response(points = point(DAY_1, 25.0)).replace(DAY_2, "2026-08-03T00:00:00Z")

        assertInvalid(wrongSeries)
        assertInvalid(wrongBounds)
        assertInvalid(wrongAsOf)
    }

    @Test
    fun unordered_duplicate_missing_and_invalid_points_are_rejected() {
        val unordered = response(points = "${point(DAY_2, 26.0)},${point(DAY_1, 25.0)}")
        val duplicate = response(points = "${point(DAY_1, 25.0)},${point(DAY_1, 26.0)}")
        val missing = response(points = "{\"timestamp\":\"$DAY_1\",\"price\":25,\"unitsPerUsd\":1}")
        val zero = response(points = point(DAY_1, 0.0))
        val negative = response(points = point(DAY_1, -1.0))
        val nonFinite = response(points = point(DAY_1, 25.0)).replace("\"priceUsd\":25.0", "\"priceUsd\":NaN")

        listOf(unordered, duplicate, missing, zero, negative, nonFinite).forEach(::assertInvalid)
    }

    @Test
    fun unknown_fields_are_ignored_but_required_fields_are_strict() {
        val withUnknown = response(points = point(DAY_1, 25.0)).replace("\"complete\":true", "\"extra\":1,\"complete\":true")
        val withoutComplete = response(points = point(DAY_1, 25.0)).replace(",\"complete\":true", "")

        assertTrue(parsePage(withUnknown, RANGE, FiatCurrency.USD) is ParsedPage.Valid)
        assertInvalid(withoutComplete)
    }

    @Test
    fun selected_fiat_price_is_parsed_and_validated_against_historical_conversion_rate() {
        val inr = FiatCurrency("INR")
        val valid =
            response(points = point(DAY_1, price = 2_000.0, priceUsd = 25.0, unitsPerUsd = 80.0), fiat = "INR")
        val contradictory = valid.replace("\"price\":2000.0", "\"price\":2100.0")

        val parsed = parsePage(valid, RANGE, inr) as ParsedPage.Valid

        assertEquals(
            BigDecimal("2000.0"),
            parsed.points
                .single()
                .second
                .fiatPerZec,
        )
        assertTrue(parsePage(contradictory, RANGE, inr) is ParsedPage.Invalid)
        assertTrue(parsePage(valid, RANGE, FiatCurrency.USD) is ParsedPage.Invalid)
    }

    private fun assertInvalid(body: String) {
        assertTrue(parsePage(body, RANGE, FiatCurrency.USD) is ParsedPage.Invalid)
    }

    private fun response(
        points: String,
        complete: Boolean = true,
        cursor: String? = null,
        fiat: String = "USD",
    ): String =
        """{"asset":"ZEC","fiat":"$fiat","resolution":"1d","from":"$RANGE_FROM","to":"$RANGE_TO","points":[$points],"availableFrom":"$DAY_1","availableTo":"$DAY_2","dataAsOf":"$DAY_2","complete":$complete${cursor?.let {
            ",\"nextCursor\":\"$it\""
        }.orEmpty()}}"""

    private fun point(
        timestamp: String,
        price: Double,
        priceUsd: Double = price,
        unitsPerUsd: Double = 1.0,
    ): String =
        """{"timestamp":"$timestamp","price":$price,"priceUsd":$priceUsd,"unitsPerUsd":$unitsPerUsd}"""

    private fun error(code: String): String = """{"error":{"code":"$code","message":"stable message"}}"""

    private companion object {
        const val DAY_1 = "2026-08-01T00:00:00Z"
        const val DAY_2 = "2026-08-02T00:00:00Z"
        const val RANGE_FROM = "2026-08-01T00:00:00Z"
        const val RANGE_TO = "2026-08-02T00:00:00Z"
        val RANGE = PriceDateRange(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-02"))
    }
}
