package co.electriccoin.zcash.ui.common.pricing.repository

import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.pricing.datasource.PricingEngineDataSource
import co.electriccoin.zcash.ui.common.pricing.model.DailyFiatPrice
import co.electriccoin.zcash.ui.common.pricing.model.DailyPriceSeries
import co.electriccoin.zcash.ui.common.pricing.model.PriceDateRange
import co.electriccoin.zcash.ui.common.pricing.model.PricingFailure
import co.electriccoin.zcash.ui.common.pricing.model.PricingResult
import co.electriccoin.zcash.ui.common.pricing.provider.CACHE_SCHEMA_VERSION
import co.electriccoin.zcash.ui.common.pricing.provider.CachedDailyPrice
import co.electriccoin.zcash.ui.common.pricing.provider.CachedDateRange
import co.electriccoin.zcash.ui.common.pricing.provider.HistoricalPriceCache
import co.electriccoin.zcash.ui.common.pricing.provider.HistoricalPriceCacheProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TreeMap

interface HistoricalPriceRepository {
    fun observe(
        range: PriceDateRange,
        fiatCurrency: FiatCurrency,
    ): Flow<HistoricalPriceState>
}

sealed interface HistoricalPriceState {
    data class Data(
        val series: DailyPriceSeries,
        val isStale: Boolean,
    ) : HistoricalPriceState

    data object Loading : HistoricalPriceState

    data class Unavailable(
        val failure: PricingFailure? = null,
    ) : HistoricalPriceState
}

class HistoricalPriceRepositoryImpl(
    private val dataSource: PricingEngineDataSource,
    private val cacheProvider: HistoricalPriceCacheProvider,
) : HistoricalPriceRepository {
    private val mutex = Mutex()
    private val refreshMutex = Mutex()
    private val snapshots = mutableMapOf<FiatCurrency, PriceCacheSnapshot>()

    override fun observe(
        range: PriceDateRange,
        fiatCurrency: FiatCurrency,
    ): Flow<HistoricalPriceState> =
        flow {
            val initial = getSnapshot(fiatCurrency)
            val cached = initial.seriesFor(range)
            if (cached != null) emit(HistoricalPriceState.Data(cached, isStale = !initial.covers(range)))

            if (initial.covers(range)) {
                if (cached == null) emit(HistoricalPriceState.Unavailable())
                return@flow
            }
            if (initial.isUnavailableFresh()) {
                if (cached == null) emit(HistoricalPriceState.Unavailable(PricingFailure.SeriesUnavailable))
                return@flow
            }
            if (initial.isRefreshSuppressed()) {
                if (cached == null) emit(HistoricalPriceState.Unavailable())
                return@flow
            }
            if (cached == null) emit(HistoricalPriceState.Loading)

            when (val result = refreshSerialized(range, fiatCurrency)) {
                is RefreshResult.Updated -> {
                    val updatedSeries = result.snapshot.seriesFor(range)
                    if (updatedSeries == null) {
                        emit(HistoricalPriceState.Unavailable())
                    } else {
                        emit(HistoricalPriceState.Data(updatedSeries, isStale = !result.snapshot.covers(range)))
                    }
                }

                is RefreshResult.Failed -> {
                    if (cached == null) emit(HistoricalPriceState.Unavailable(result.failure))
                }
            }
        }

    private suspend fun getSnapshot(fiatCurrency: FiatCurrency): PriceCacheSnapshot =
        mutex.withLock {
            snapshots[fiatCurrency]
                ?: PriceCacheSnapshot
                    .from(cacheProvider.load(fiatCurrency), fiatCurrency)
                    .also { snapshots[fiatCurrency] = it }
        }

    private suspend fun refreshSerialized(
        range: PriceDateRange,
        fiatCurrency: FiatCurrency,
    ): RefreshResult =
        refreshMutex.withLock {
            val latest = getSnapshot(fiatCurrency)
            when {
                latest.covers(range) -> RefreshResult.Updated(latest)
                latest.isUnavailableFresh() -> RefreshResult.Failed(PricingFailure.SeriesUnavailable)
                latest.isRefreshSuppressed() -> RefreshResult.Failed(null)
                else -> refresh(range, fiatCurrency)
            }
        }

    private suspend fun refresh(
        range: PriceDateRange,
        fiatCurrency: FiatCurrency,
    ): RefreshResult {
        var working = getSnapshot(fiatCurrency)
        val uncovered = working.uncoveredRanges(range)
        for (missingRange in uncovered) {
            val fetched = dataSource.getDailyPrices(missingRange, fiatCurrency)
            val result =
                if (fetched is PricingResult.Success && fetched.value.fiatCurrency != fiatCurrency) {
                    PricingResult.Failure(PricingFailure.InvalidResponse("series fiat currency changed"))
                } else {
                    fetched
                }
            when (result) {
                is PricingResult.Success -> {
                    working = working.upsert(result.value, missingRange, Instant.now())
                    persist(fiatCurrency, working)
                }

                is PricingResult.Failure -> {
                    working =
                        if (result.failure == PricingFailure.SeriesUnavailable) {
                            working.markUnavailable(Instant.now())
                        } else {
                            working.suppressRefreshUntil(Instant.now().plus(FAILURE_COOLDOWN))
                        }
                    persist(fiatCurrency, working)
                    return RefreshResult.Failed(result.failure)
                }
            }
        }
        if (working.covers(range) && working.refreshNotBefore != null) {
            working = working.clearRefreshSuppression()
            persist(fiatCurrency, working)
        }
        return RefreshResult.Updated(working)
    }

    private suspend fun persist(
        fiatCurrency: FiatCurrency,
        updated: PriceCacheSnapshot,
    ) {
        mutex.withLock { snapshots[fiatCurrency] = updated }
        try {
            cacheProvider.store(fiatCurrency, updated.toCache())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Twig.warn(e) { "HistoricalPriceRepository: price cache write failed" }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException
        ) {
            Twig.warn(e) { "HistoricalPriceRepository: price cache write failed" }
        }
    }
}

private sealed interface RefreshResult {
    data class Updated(
        val snapshot: PriceCacheSnapshot,
    ) : RefreshResult

    data class Failed(
        val failure: PricingFailure?,
    ) : RefreshResult
}

private data class PriceCacheSnapshot(
    val fiatCurrency: FiatCurrency,
    val points: Map<LocalDate, DailyFiatPrice>,
    val completedRanges: List<PriceDateRange>,
    val availableFrom: LocalDate?,
    val availableTo: LocalDate?,
    val dataAsOf: Instant?,
    val unavailableCheckedAt: Instant?,
    val refreshNotBefore: Instant?,
) {
    fun covers(range: PriceDateRange): Boolean =
        completedRanges.any { completed -> !completed.from.isAfter(range.from) && !completed.to.isBefore(range.to) }

    fun uncoveredRanges(range: PriceDateRange): List<PriceDateRange> {
        val uncovered = ArrayList<PriceDateRange>()
        var start: LocalDate? = null
        var date = range.from
        while (!date.isAfter(range.to)) {
            val covered = completedRanges.any { !date.isBefore(it.from) && !date.isAfter(it.to) }
            if (!covered && start == null) start = date
            if (covered && start != null) {
                uncovered += PriceDateRange(start, date.minusDays(1))
                start = null
            }
            date = date.plusDays(1)
        }
        if (start != null) uncovered += PriceDateRange(start, range.to)
        return uncovered
    }

    fun seriesFor(range: PriceDateRange): DailyPriceSeries? {
        val from = availableFrom
        val to = availableTo
        val asOf = dataAsOf
        return if (from == null || to == null || asOf == null) {
            null
        } else {
            DailyPriceSeries(
                fiatCurrency = fiatCurrency,
                points = points.values.filter { !it.date.isBefore(range.from) && !it.date.isAfter(range.to) },
                availableFrom = from,
                availableTo = to,
                dataAsOf = asOf,
            )
        }
    }

    fun upsert(
        series: DailyPriceSeries,
        completedRange: PriceDateRange,
        now: Instant,
    ): PriceCacheSnapshot {
        val mergedPoints = TreeMap(points)
        series.points.forEach { mergedPoints[it.date] = it }
        val completedFrom = maxOf(completedRange.from, series.availableFrom)
        val completedTo = minOf(completedRange.to, series.availableTo)
        val completedBounds =
            if (completedFrom.isAfter(completedTo)) null else PriceDateRange(completedFrom, completedTo)
        // The API's `complete` flag means pagination is finished, not that every daily row exists.
        // Record only dates actually returned so an ingestion gap remains eligible for a later backfill.
        val fetchedRanges =
            completedBounds
                ?.let { bounds -> rangesForDates(series.points.map(DailyFiatPrice::date), bounds) }
                .orEmpty()
        val mergedRanges = mergeRanges(completedRanges + fetchedRanges)
        val requestCovered = mergedRanges.any { it.contains(completedRange) }
        return copy(
            points = mergedPoints.toMap(),
            completedRanges = mergedRanges,
            availableFrom = availableFrom?.let { minOf(it, series.availableFrom) } ?: series.availableFrom,
            availableTo = availableTo?.let { maxOf(it, series.availableTo) } ?: series.availableTo,
            dataAsOf = dataAsOf?.let { maxOf(it, series.dataAsOf) } ?: series.dataAsOf,
            unavailableCheckedAt = null,
            refreshNotBefore = if (requestCovered) null else now.plus(INCOMPLETE_SERIES_COOLDOWN),
        )
    }

    fun markUnavailable(now: Instant): PriceCacheSnapshot =
        copy(unavailableCheckedAt = now, refreshNotBefore = null)

    fun isUnavailableFresh(now: Instant = Instant.now()): Boolean {
        val checked = unavailableCheckedAt ?: return false
        return checked.atZone(ZoneOffset.UTC).toLocalDate() == now.atZone(ZoneOffset.UTC).toLocalDate()
    }

    fun isRefreshSuppressed(now: Instant = Instant.now()): Boolean = refreshNotBefore?.isAfter(now) == true

    fun suppressRefreshUntil(instant: Instant): PriceCacheSnapshot = copy(refreshNotBefore = instant)

    fun clearRefreshSuppression(): PriceCacheSnapshot = copy(refreshNotBefore = null)

    fun toCache(): HistoricalPriceCache =
        HistoricalPriceCache(
            schemaVersion = CACHE_SCHEMA_VERSION,
            fiatCurrencyCode = fiatCurrency.code,
            availableFrom = availableFrom?.toString(),
            availableTo = availableTo?.toString(),
            dataAsOf = dataAsOf?.toString(),
            points = points.values.map { CachedDailyPrice(it.date.toString(), it.fiatPerZec.toPlainString()) },
            completedRanges = completedRanges.map { CachedDateRange(it.from.toString(), it.to.toString()) },
            lastCompletedRequestDate = completedRanges.maxOfOrNull(PriceDateRange::to)?.toString(),
            unavailableCheckedAt = unavailableCheckedAt?.toString(),
            refreshNotBefore = refreshNotBefore?.toString(),
        )

    companion object {
        fun from(
            cache: HistoricalPriceCache?,
            fiatCurrency: FiatCurrency,
        ): PriceCacheSnapshot {
            if (cache == null || cache.fiatCurrencyCode != fiatCurrency.code) return empty(fiatCurrency)
            return runCatching {
                val prices =
                    cache.points.associate { cached ->
                        val date = LocalDate.parse(cached.date)
                        val price = BigDecimal(cached.fiatPerZec)
                        require(price.signum() > 0)
                        date to DailyFiatPrice(date, price)
                    }
                val parsedRanges =
                    cache.completedRanges.map { cached ->
                        PriceDateRange(LocalDate.parse(cached.from), LocalDate.parse(cached.to))
                    }
                val from = cache.availableFrom?.let(LocalDate::parse)
                val to = cache.availableTo?.let(LocalDate::parse)
                val asOf = cache.dataAsOf?.let(Instant::parse)
                require((from == null) == (to == null) && (to == null) == (asOf == null))
                require(from == null || !from.isAfter(to))
                val ranges =
                    if (from == null || to == null) {
                        emptyList()
                    } else {
                        val availableRange = PriceDateRange(from, to)
                        val previouslyCompletedDates =
                            prices.keys.filter { date ->
                                date in availableRange && parsedRanges.any { completed -> date in completed }
                            }
                        rangesForDates(previouslyCompletedDates, availableRange)
                    }
                PriceCacheSnapshot(
                    fiatCurrency = fiatCurrency,
                    points = TreeMap(prices).toMap(),
                    completedRanges = mergeRanges(ranges),
                    availableFrom = from,
                    availableTo = to,
                    dataAsOf = asOf,
                    unavailableCheckedAt = cache.unavailableCheckedAt?.let(Instant::parse),
                    refreshNotBefore = cache.refreshNotBefore?.let(Instant::parse),
                )
            }.getOrDefault(empty(fiatCurrency))
        }

        private fun empty(fiatCurrency: FiatCurrency) =
            PriceCacheSnapshot(
                fiatCurrency = fiatCurrency,
                points = emptyMap(),
                completedRanges = emptyList(),
                availableFrom = null,
                availableTo = null,
                dataAsOf = null,
                unavailableCheckedAt = null,
                refreshNotBefore = null,
            )
    }
}

private fun mergeRanges(ranges: List<PriceDateRange>): List<PriceDateRange> {
    val sorted = ranges.sortedBy(PriceDateRange::from)
    if (sorted.isEmpty()) return emptyList()
    val merged = mutableListOf(sorted.first())
    sorted.drop(1).forEach { range ->
        val last = merged.last()
        if (!range.from.isAfter(last.to.plusDays(1))) {
            merged[merged.lastIndex] = PriceDateRange(last.from, maxOf(last.to, range.to))
        } else {
            merged += range
        }
    }
    return merged
}

private fun rangesForDates(
    dates: Iterable<LocalDate>,
    bounds: PriceDateRange,
): List<PriceDateRange> =
    mergeRanges(
        dates
            .asSequence()
            .filter { it in bounds }
            .distinct()
            .map { PriceDateRange(it, it) }
            .toList()
    )

private fun PriceDateRange.contains(other: PriceDateRange): Boolean =
    !from.isAfter(other.from) && !to.isBefore(other.to)

private operator fun PriceDateRange.contains(date: LocalDate): Boolean =
    !date.isBefore(from) && !date.isAfter(to)

private const val FAILURE_COOLDOWN_MINUTES = 5L
private const val INCOMPLETE_SERIES_COOLDOWN_HOURS = 1L
private val FAILURE_COOLDOWN: Duration = Duration.ofMinutes(FAILURE_COOLDOWN_MINUTES)
private val INCOMPLETE_SERIES_COOLDOWN: Duration = Duration.ofHours(INCOMPLETE_SERIES_COOLDOWN_HOURS)
