package co.electriccoin.zcash.ui.common.pricing.datasource

import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.common.pricing.model.PriceDateRange
import co.electriccoin.zcash.ui.common.pricing.model.PricingFailure
import co.electriccoin.zcash.ui.common.pricing.model.PricingResult
import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

@Suppress("MaxLineLength")
class PricingEngineDataSourceTest {
    @Test
    fun paginates_with_inclusive_cursor_and_canonical_parameter_order() =
        runTest {
            val urls = mutableListOf<String>()
            var requestCount = 0
            val source =
                dataSource { request ->
                    urls += request.url.toString()
                    requestCount++
                    when (requestCount) {
                        1 -> successResponse(points = "${point(DAY_1, 25)},${point(DAY_2, 26)}", complete = false, cursor = DAY_3)
                        else -> successResponse(from = "2026-08-03", points = point(DAY_3, 27))
                    }
                }

            val result = source.getDailyPrices(RANGE, FiatCurrency.USD) as PricingResult.Success

            assertEquals(listOf("25.0", "26.0", "27.0"), result.value.points.map { it.fiatPerZec.toPlainString() })
            assertEquals(
                "https://zapp-pricing-engine.majorworker.workers.dev/v1/prices?asset=ZEC&fiat=USD&resolution=1d&from=2026-08-01&to=2026-08-03&limit=1000",
                urls.first(),
            )
            assertTrue(urls.last().contains("from=2026-08-03&to=2026-08-03&limit=1000"))
        }

    @Test
    fun repeated_cursor_is_invalid_and_is_not_retried() =
        runTest {
            var requests = 0
            val source =
                dataSource {
                    requests++
                    successResponse(points = point(DAY_1, 25), complete = false, cursor = DAY_1)
                }

            val result = source.getDailyPrices(RANGE, FiatCurrency.USD)

            assertTrue(result is PricingResult.Failure && result.failure is PricingFailure.InvalidResponse)
            assertEquals(1, requests)
        }

    @Test
    fun retrying_a_late_page_does_not_refetch_completed_pages() =
        runTest {
            var requests = 0
            val source =
                dataSource {
                    requests++
                    when (requests) {
                        1 -> successResponse(points = "${point(DAY_1, 25)},${point(DAY_2, 26)}", complete = false, cursor = DAY_3)
                        2 -> throw IOException("transient")
                        else -> successResponse(from = "2026-08-03", points = point(DAY_3, 27))
                    }
                }

            val result = source.getDailyPrices(RANGE, FiatCurrency.USD)

            assertTrue(result is PricingResult.Success)
            assertEquals(3, requests)
        }

    @Test
    fun complete_empty_response_is_returned_as_an_empty_series() =
        runTest {
            val result = dataSource { successResponse(points = "") }.getDailyPrices(RANGE, FiatCurrency.USD)

            assertTrue(result is PricingResult.Success && result.value.points.isEmpty())
        }

    @Test
    fun series_unavailable_uses_structured_code_not_message() =
        runTest {
            val source =
                dataSource {
                    """{"error":{"code":"SERIES_UNAVAILABLE","message":"this text may change"}}""" to
                        HttpStatusCode.NotFound
                }

            val result = source.getDailyPrices(RANGE, FiatCurrency.USD)

            assertEquals(PricingResult.Failure(PricingFailure.SeriesUnavailable), result)
        }

    @Test
    fun io_failure_maps_to_network_failure() =
        runTest {
            var requests = 0
            val engine =
                MockEngine {
                    requests++
                    throw IOException("offline")
                }
            val source = PricingEngineDataSourceImpl(FakeHttpClientProvider(HttpClient(engine) { expectSuccess = true }))

            val result = source.getDailyPrices(RANGE, FiatCurrency.USD)

            assertTrue(result is PricingResult.Failure && result.failure is PricingFailure.Network)
            assertEquals(2, requests)
        }

    @Test
    @Suppress("TooGenericExceptionThrown")
    fun sdk_style_runtime_transport_failure_maps_to_network_failure() =
        runTest {
            var requests = 0
            val engine =
                MockEngine {
                    requests++
                    throw RuntimeException("Tor error: tor: operation timed out at exit")
                }
            val source = PricingEngineDataSourceImpl(FakeHttpClientProvider(HttpClient(engine) { expectSuccess = true }))

            val result = source.getDailyPrices(RANGE, FiatCurrency.USD)

            assertTrue(result is PricingResult.Failure && result.failure is PricingFailure.Network)
            assertEquals(2, requests)
        }

    @Test
    fun selected_fiat_is_sent_through_tor_and_returned_without_current_rate_conversion() =
        runTest {
            val urls = mutableListOf<String>()
            val source =
                dataSource { request ->
                    urls += request.url.toString()
                    successResponse(
                        points = point(DAY_1, price = 2_000, priceUsd = 25, unitsPerUsd = 80),
                        fiat = "INR",
                    )
                }

            val result = source.getDailyPrices(RANGE, FiatCurrency("INR")) as PricingResult.Success

            assertEquals(
                "2000.0",
                result.value.points
                    .single()
                    .fiatPerZec
                    .toPlainString(),
            )
            assertTrue(urls.single().contains("asset=ZEC&fiat=INR&resolution=1d"))
        }

    private fun dataSource(handler: (io.ktor.client.request.HttpRequestData) -> Pair<String, HttpStatusCode>): PricingEngineDataSource {
        val engine =
            MockEngine { request ->
                val (body, status) = handler(request)
                respond(
                    content = body,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        return PricingEngineDataSourceImpl(FakeHttpClientProvider(HttpClient(engine) { expectSuccess = true }))
    }

    private fun successResponse(
        from: String = "2026-08-01",
        points: String,
        complete: Boolean = true,
        cursor: String? = null,
        fiat: String = "USD",
    ): Pair<String, HttpStatusCode> =
        """{"asset":"ZEC","fiat":"$fiat","resolution":"1d","from":"${from}T00:00:00.000Z","to":"2026-08-03T00:00:00.000Z","points":[$points],"availableFrom":"$DAY_1","availableTo":"$DAY_3","dataAsOf":"$DAY_3","complete":$complete${cursor?.let {
            ",\"nextCursor\":\"$it\""
        }.orEmpty()}}""" to
            HttpStatusCode.OK

    private fun point(
        timestamp: String,
        price: Int,
        priceUsd: Int = price,
        unitsPerUsd: Int = 1,
    ): String =
        """{"timestamp":"$timestamp","price":$price,"priceUsd":$priceUsd,"unitsPerUsd":$unitsPerUsd}"""

    private class FakeHttpClientProvider(
        private val client: HttpClient,
    ) : HttpClientProvider {
        override suspend fun create(): HttpClient = error("Pricing requests must use the Tor-only client")

        override suspend fun createTor(): HttpClient = client
    }

    private companion object {
        const val DAY_1 = "2026-08-01T00:00:00Z"
        const val DAY_2 = "2026-08-02T00:00:00Z"
        const val DAY_3 = "2026-08-03T00:00:00Z"
        val RANGE = PriceDateRange(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-03"))
    }
}
