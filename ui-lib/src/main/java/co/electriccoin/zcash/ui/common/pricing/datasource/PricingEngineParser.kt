package co.electriccoin.zcash.ui.common.pricing.datasource

import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.common.pricing.model.DailyFiatPrice
import co.electriccoin.zcash.ui.common.pricing.model.PriceDateRange
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlin.math.abs
import kotlin.math.max

internal sealed interface ParsedPage {
    data class Valid(
        val points: List<Pair<Instant, DailyFiatPrice>>,
        val metadata: PageMetadata,
        val complete: Boolean,
        val nextCursor: Instant?,
    ) : ParsedPage

    data class Invalid(
        val reason: String,
    ) : ParsedPage
}

internal data class PageMetadata(
    val availableFrom: LocalDate,
    val availableTo: LocalDate,
    val dataAsOf: Instant,
)

private data class ParsedHeader(
    val responseFrom: Instant,
    val responseTo: Instant,
    val availableFrom: Instant,
    val availableTo: Instant,
    val metadata: PageMetadata,
)

private data class ResponseBounds(
    val from: Instant,
    val to: Instant,
)

private data class Availability(
    val from: Instant,
    val to: Instant,
    val metadata: PageMetadata,
)

private data class Pagination(
    val complete: Boolean,
    val nextCursor: Instant?,
)

private class InvalidPageException(
    val reason: String,
) : Exception()

internal fun parsePage(
    body: String,
    requestedRange: PriceDateRange,
    requestedFiat: FiatCurrency,
): ParsedPage =
    try {
        parsePageOrThrow(body, requestedRange, requestedFiat)
    } catch (error: InvalidPageException) {
        ParsedPage.Invalid(error.reason)
    }

private fun parsePageOrThrow(
    body: String,
    requestedRange: PriceDateRange,
    requestedFiat: FiatCurrency,
): ParsedPage.Valid {
    val dto =
        try {
            PAGE_JSON.decodeFromString<PriceSeriesDto>(body)
        } catch (_: SerializationException) {
            throw InvalidPageException("malformed success response")
        }
    val bounds = parseResponseBounds(dto, requestedRange, requestedFiat)
    val availability = parseAvailability(dto)
    val header =
        ParsedHeader(
            responseFrom = bounds.from,
            responseTo = bounds.to,
            availableFrom = availability.from,
            availableTo = availability.to,
            metadata = availability.metadata,
        )
    val points = parsePoints(dto.points, requestedFiat, header)
    val pagination = parsePagination(dto)
    return ParsedPage.Valid(
        points = points,
        metadata = header.metadata,
        complete = pagination.complete,
        nextCursor = pagination.nextCursor,
    )
}

private fun parseResponseBounds(
    dto: PriceSeriesDto,
    requestedRange: PriceDateRange,
    requestedFiat: FiatCurrency,
): ResponseBounds {
    if (dto.asset != ASSET || dto.fiat != requestedFiat.code || dto.resolution != RESOLUTION) {
        throw InvalidPageException("response series does not match ZEC/${requestedFiat.code}/1d")
    }
    val from = dto.from.parseInstantOrInvalid("invalid from")
    val to = dto.to.parseInstantOrInvalid("invalid to")
    val requestedFrom = requestedRange.from.atStartOfDay().toInstant(ZoneOffset.UTC)
    val requestedTo = requestedRange.to.atStartOfDay().toInstant(ZoneOffset.UTC)
    if (from != requestedFrom || to != requestedTo) {
        throw InvalidPageException("response bounds contradict the request")
    }
    return ResponseBounds(from, to)
}

private fun parseAvailability(dto: PriceSeriesDto): Availability {
    val from = dto.availableFrom.parseInstantOrInvalid("invalid availableFrom")
    val to = dto.availableTo.parseInstantOrInvalid("invalid availableTo")
    val dataAsOf = dto.dataAsOf.parseInstantOrInvalid("invalid dataAsOf")
    if (!from.isUtcDayBoundary() || !to.isUtcDayBoundary()) {
        throw InvalidPageException("daily availability is not aligned to UTC dates")
    }
    val fromDate = from.atZone(ZoneOffset.UTC).toLocalDate()
    val toDate = to.atZone(ZoneOffset.UTC).toLocalDate()
    if (fromDate.isAfter(toDate) || dataAsOf != to) {
        throw InvalidPageException("availability metadata is contradictory")
    }
    return Availability(from, to, PageMetadata(fromDate, toDate, dataAsOf))
}

private fun parsePagination(dto: PriceSeriesDto): Pagination {
    val complete = dto.complete ?: throw InvalidPageException("missing complete")
    val nextCursor = dto.nextCursor?.parseInstantOrInvalid("invalid nextCursor")
    if (complete && nextCursor != null) throw InvalidPageException("complete response contains nextCursor")
    return Pagination(complete, nextCursor)
}

private fun parsePoints(
    pointDtos: List<PricePointDto>?,
    requestedFiat: FiatCurrency,
    header: ParsedHeader,
): List<Pair<Instant, DailyFiatPrice>> {
    val resolvedPointDtos = pointDtos ?: throw InvalidPageException("missing points")
    val parsedPoints = ArrayList<Pair<Instant, DailyFiatPrice>>(resolvedPointDtos.size)
    var previousTimestamp: Instant? = null
    for (point in resolvedPointDtos) {
        val timestamp = point.timestamp.parseInstantOrInvalid("invalid point timestamp")
        val price = parsePrice(point, requestedFiat)
        val pointError =
            when {
                !timestamp.isValidFor(header) -> {
                    "point timestamp contradicts metadata"
                }

                previousTimestamp != null && !timestamp.isAfter(previousTimestamp) -> {
                    "point timestamps are unordered or duplicated"
                }

                else -> {
                    null
                }
            }
        if (pointError != null) throw InvalidPageException(pointError)
        previousTimestamp = timestamp
        parsedPoints +=
            timestamp to
            DailyFiatPrice(
                date = timestamp.atZone(ZoneOffset.UTC).toLocalDate(),
                fiatPerZec = BigDecimal.valueOf(price),
            )
    }
    return parsedPoints
}

private fun parsePrice(
    point: PricePointDto,
    requestedFiat: FiatCurrency,
): Double {
    val prices = listOfNotNull(point.price, point.priceUsd, point.unitsPerUsd)
    if (prices.size != PRICE_FIELD_COUNT || prices.any { !it.isFinite() || it <= 0.0 }) {
        throw InvalidPageException("point contains a non-positive or non-finite price")
    }
    val (price, priceUsd, unitsPerUsd) = prices
    val expectedFiatPrice = priceUsd * unitsPerUsd
    val tolerance = max(abs(expectedFiatPrice), 1.0) * PRICE_RELATIVE_TOLERANCE
    val contradiction =
        when {
            requestedFiat == FiatCurrency.USD &&
                BigDecimal.valueOf(unitsPerUsd).compareTo(BigDecimal.ONE) != 0 -> {
                "USD point has a non-unit conversion rate"
            }

            abs(price - expectedFiatPrice) > tolerance -> {
                "point price contradicts its USD price and fiat conversion rate"
            }

            else -> {
                null
            }
        }
    if (contradiction != null) throw InvalidPageException(contradiction)
    return price
}

private fun Instant.isValidFor(header: ParsedHeader): Boolean =
    isUtcDayBoundary() &&
        this in header.responseFrom..header.responseTo &&
        this in header.availableFrom..header.availableTo

private fun String?.parseInstantOrInvalid(reason: String): Instant {
    val value = this ?: throw InvalidPageException(reason)
    return try {
        Instant.parse(value)
    } catch (_: DateTimeParseException) {
        throw InvalidPageException(reason)
    }
}

private fun Instant.isUtcDayBoundary(): Boolean =
    this == atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC)

@Serializable
private data class PriceSeriesDto(
    val asset: String? = null,
    val fiat: String? = null,
    val resolution: String? = null,
    val from: String? = null,
    val to: String? = null,
    val points: List<PricePointDto>? = null,
    val availableFrom: String? = null,
    val availableTo: String? = null,
    val dataAsOf: String? = null,
    val complete: Boolean? = null,
    val nextCursor: String? = null,
)

@Serializable
private data class PricePointDto(
    val timestamp: String? = null,
    val price: Double? = null,
    val priceUsd: Double? = null,
    val unitsPerUsd: Double? = null,
)

private val PAGE_JSON = Json { ignoreUnknownKeys = true }
private const val PRICE_FIELD_COUNT = 3
private const val PRICE_RELATIVE_TOLERANCE = 1e-12
