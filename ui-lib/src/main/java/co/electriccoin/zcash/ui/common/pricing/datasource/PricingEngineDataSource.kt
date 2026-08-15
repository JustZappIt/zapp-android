package co.electriccoin.zcash.ui.common.pricing.datasource

import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.common.pricing.model.DailyFiatPrice
import co.electriccoin.zcash.ui.common.pricing.model.DailyPriceSeries
import co.electriccoin.zcash.ui.common.pricing.model.PriceDateRange
import co.electriccoin.zcash.ui.common.pricing.model.PricingFailure
import co.electriccoin.zcash.ui.common.pricing.model.PricingResult
import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import co.electriccoin.zcash.ui.common.provider.PRICING_ENGINE_HOST
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TreeMap
import kotlin.random.Random

interface PricingEngineDataSource {
    suspend fun getDailyPrices(
        range: PriceDateRange,
        fiatCurrency: FiatCurrency,
    ): PricingResult<DailyPriceSeries>
}

class PricingEngineDataSourceImpl(
    private val httpClientProvider: HttpClientProvider,
) : PricingEngineDataSource {
    override suspend fun getDailyPrices(
        range: PriceDateRange,
        fiatCurrency: FiatCurrency,
    ): PricingResult<DailyPriceSeries> =
        withContext(Dispatchers.IO) {
            try {
                // Market-price requests follow the same privacy contract as spot exchange rates:
                // they must never expose the user's IP address to a pricing service.
                httpClientProvider.createTor().use { client ->
                    client.getDailyPrices(range = range, fiatCurrency = fiatCurrency)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ResponseException) {
                mapHttpFailure(e)
            } catch (e: IOException) {
                PricingResult.Failure(PricingFailure.Network(e))
            } catch (e: IllegalArgumentException) {
                PricingResult.Failure(PricingFailure.InvalidResponse(e.message ?: "invalid pricing response"))
            }
        }

    private suspend fun HttpClient.getDailyPrices(
        range: PriceDateRange,
        fiatCurrency: FiatCurrency,
    ): PricingResult<DailyPriceSeries> =
        getDailyPricePage(
            range = range,
            fiatCurrency = fiatCurrency,
            requestFrom = range.from,
            points = TreeMap(),
            expectedMetadata = null,
            pageIndex = 0,
        )

    @Suppress("ReturnCount")
    private suspend fun HttpClient.getDailyPricePage(
        range: PriceDateRange,
        fiatCurrency: FiatCurrency,
        requestFrom: LocalDate,
        points: TreeMap<Instant, DailyFiatPrice>,
        expectedMetadata: PageMetadata?,
        pageIndex: Int,
    ): PricingResult<DailyPriceSeries> {
        if (pageIndex >= MAX_PAGE_COUNT) {
            return PricingResult.Failure(PricingFailure.InvalidResponse("price series exceeded the page limit"))
        }
        val response = getPage(requestFrom = requestFrom, range = range, fiatCurrency = fiatCurrency)
        val page =
            when (
                val parsed =
                    parsePage(
                        body = response.bodyAsText(),
                        requestedRange = PriceDateRange(requestFrom, range.to),
                        requestedFiat = fiatCurrency,
                    )
            ) {
                is ParsedPage.Invalid -> {
                    return PricingResult.Failure(PricingFailure.InvalidResponse(parsed.reason))
                }

                is ParsedPage.Valid -> {
                    parsed
                }
            }
        if (expectedMetadata != null && expectedMetadata != page.metadata) {
            return PricingResult.Failure(
                PricingFailure.InvalidResponse("availability metadata changed between pages")
            )
        }
        page.points.forEach { point -> points[point.first] = point.second }
        if (page.complete) return page.toSeries(fiatCurrency = fiatCurrency, points = points)
        val nextFrom =
            when (val nextPage = page.nextRequestDate(currentFrom = requestFrom, requestedTo = range.to)) {
                is NextPage.Invalid -> {
                    return PricingResult.Failure(PricingFailure.InvalidResponse(nextPage.reason))
                }

                is NextPage.Valid -> {
                    nextPage.from
                }
            }
        return getDailyPricePage(
            range = range,
            fiatCurrency = fiatCurrency,
            requestFrom = nextFrom,
            points = points,
            expectedMetadata = page.metadata,
            pageIndex = pageIndex + 1,
        )
    }

    private suspend fun HttpClient.getPage(
        requestFrom: LocalDate,
        range: PriceDateRange,
        fiatCurrency: FiatCurrency,
    ): HttpResponse {
        var attempt = 0
        while (true) {
            try {
                return executePriceRequest(requestFrom, range, fiatCurrency)
            } catch (e: ResponseException) {
                if (e.response.status.value < MIN_SERVER_ERROR_STATUS || attempt >= MAX_RETRY_COUNT) throw e
            } catch (e: IOException) {
                if (attempt >= MAX_RETRY_COUNT) throw e
            }
            delay(RETRY_BASE_DELAY_MILLIS + Random.nextLong(RETRY_JITTER_MILLIS + 1L))
            attempt++
        }
    }

    private suspend fun HttpClient.executePriceRequest(
        requestFrom: LocalDate,
        range: PriceDateRange,
        fiatCurrency: FiatCurrency,
    ): HttpResponse =
        try {
            get(PRICING_ENGINE_URL) {
                // Keep this order stable: it is part of the Worker's CDN cache key.
                parameter("asset", ASSET)
                parameter("fiat", fiatCurrency.code)
                parameter("resolution", RESOLUTION)
                parameter("from", requestFrom.toString())
                parameter("to", range.to.toString())
                parameter("limit", PAGE_LIMIT)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException
        ) {
            // The native Tor engine currently exposes transport failures (including exit timeouts)
            // as RuntimeException. Keep this boundary around the transport call so parser and
            // programming failures still surface instead of being mislabeled as network errors.
            throw PricingTransportException(e)
        }

    private suspend fun mapHttpFailure(exception: ResponseException): PricingResult.Failure {
        val status = exception.response.status.value
        val body =
            runCatching { exception.response.bodyAsText() }.getOrNull()
                ?: return PricingResult.Failure(PricingFailure.Http(status))
        return PricingResult.Failure(parsePricingError(status, body))
    }
}

private class PricingTransportException(
    cause: RuntimeException,
) : IOException(cause)

private sealed interface NextPage {
    data class Valid(
        val from: LocalDate,
    ) : NextPage

    data class Invalid(
        val reason: String,
    ) : NextPage
}

private fun ParsedPage.Valid.nextRequestDate(
    currentFrom: LocalDate,
    requestedTo: LocalDate,
): NextPage {
    val cursor = nextCursor
    val lastTimestamp = points.lastOrNull()?.first
    return when {
        cursor == null -> {
            NextPage.Invalid("incomplete page has no nextCursor")
        }

        lastTimestamp == null -> {
            NextPage.Invalid("incomplete page has no points")
        }

        !cursor.isAfter(lastTimestamp) -> {
            NextPage.Invalid("nextCursor did not advance")
        }

        else -> {
            val nextFrom = cursor.atZone(ZoneOffset.UTC).toLocalDate()
            if (!nextFrom.isAfter(currentFrom) || nextFrom.isAfter(requestedTo)) {
                NextPage.Invalid("nextCursor is repeated, regressing, or out of range")
            } else {
                NextPage.Valid(nextFrom)
            }
        }
    }
}

private fun ParsedPage.Valid.toSeries(
    fiatCurrency: FiatCurrency,
    points: TreeMap<Instant, DailyFiatPrice>,
): PricingResult.Success<DailyPriceSeries> =
    PricingResult.Success(
        DailyPriceSeries(
            fiatCurrency = fiatCurrency,
            points = points.values.toList(),
            availableFrom = metadata.availableFrom,
            availableTo = metadata.availableTo,
            dataAsOf = metadata.dataAsOf,
        )
    )

internal fun parsePricingError(
    status: Int,
    body: String,
): PricingFailure {
    val envelope = runCatching { JSON.decodeFromString<PricingErrorEnvelopeDto>(body) }.getOrNull()
    val code = envelope?.error?.code
    return when {
        envelope == null -> PricingFailure.InvalidResponse("malformed error response")
        code == null -> PricingFailure.InvalidResponse("missing error code")
        code == "SERIES_UNAVAILABLE" -> PricingFailure.SeriesUnavailable
        code in STABLE_HTTP_ERROR_CODES -> PricingFailure.Http(status)
        else -> PricingFailure.InvalidResponse("unknown error code: $code")
    }
}

@Serializable
private data class PricingErrorEnvelopeDto(
    val error: PricingErrorDto? = null,
)

@Serializable
private data class PricingErrorDto(
    val code: String? = null,
    val message: String? = null,
)

private val JSON = Json { ignoreUnknownKeys = true }
private const val PRICING_ENGINE_URL = "https://$PRICING_ENGINE_HOST/v1/prices"
internal const val ASSET = "ZEC"
internal const val RESOLUTION = "1d"
private const val PAGE_LIMIT = 1_000
private const val MAX_PAGE_COUNT = 20
private const val MAX_RETRY_COUNT = 1
private const val RETRY_BASE_DELAY_MILLIS = 500L
private const val RETRY_JITTER_MILLIS = 500L
private const val MIN_SERVER_ERROR_STATUS = 500
private val STABLE_HTTP_ERROR_CODES = setOf("INVALID_QUERY", "METHOD_NOT_ALLOWED", "ROUTE_NOT_FOUND", "INTERNAL_ERROR")
