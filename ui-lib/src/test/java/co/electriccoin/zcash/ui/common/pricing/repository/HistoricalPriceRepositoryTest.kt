package co.electriccoin.zcash.ui.common.pricing.repository

import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.common.pricing.datasource.PricingEngineDataSource
import co.electriccoin.zcash.ui.common.pricing.model.DailyFiatPrice
import co.electriccoin.zcash.ui.common.pricing.model.DailyPriceSeries
import co.electriccoin.zcash.ui.common.pricing.model.PriceDateRange
import co.electriccoin.zcash.ui.common.pricing.model.PricingFailure
import co.electriccoin.zcash.ui.common.pricing.model.PricingResult
import co.electriccoin.zcash.ui.common.pricing.provider.CachedDailyPrice
import co.electriccoin.zcash.ui.common.pricing.provider.CachedDateRange
import co.electriccoin.zcash.ui.common.pricing.provider.HistoricalPriceCache
import co.electriccoin.zcash.ui.common.pricing.provider.HistoricalPriceCacheProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class HistoricalPriceRepositoryTest {
    @Test
    fun cold_load_fetches_and_persists() =
        runTest {
            val cache = FakeCacheProvider()
            val source = FakeDataSource()
            val states = HistoricalPriceRepositoryImpl(source, cache).observe(RANGE).toList()

            assertEquals(1, source.requests.size)
            assertTrue(states.first() is HistoricalPriceState.Loading)
            assertTrue(states.last() is HistoricalPriceState.Data)
            assertEquals(3, cache.value?.points?.size)
        }

    @Test
    fun warm_covered_load_makes_no_network_request() =
        runTest {
            val cache = FakeCacheProvider(cache(RANGE))
            val source = FakeDataSource()
            val states = HistoricalPriceRepositoryImpl(source, cache).observe(RANGE).toList()

            assertTrue(states.single() is HistoricalPriceState.Data)
            assertTrue(source.requests.isEmpty())
        }

    @Test
    fun only_incremental_missing_range_is_fetched() =
        runTest {
            val covered = PriceDateRange(DATE_1, DATE_2)
            val requested = PriceDateRange(DATE_1, DATE_3.plusDays(1))
            val source = FakeDataSource()
            HistoricalPriceRepositoryImpl(source, FakeCacheProvider(cache(covered))).observe(requested).toList()

            assertEquals(listOf(PriceDateRange(DATE_3, DATE_3.plusDays(1))), source.requests)
        }

    @Test
    fun semantically_corrupt_cache_is_treated_as_empty() =
        runTest {
            val corrupt =
                HistoricalPriceCache(
                    fiatCurrencyCode = FiatCurrency.USD.code,
                    points = listOf(CachedDailyPrice("not-a-date", "not-a-price")),
                )
            val source = FakeDataSource()
            HistoricalPriceRepositoryImpl(source, FakeCacheProvider(corrupt)).observe(RANGE).toList()

            assertEquals(listOf(RANGE), source.requests)
        }

    @Test
    fun concurrent_collectors_coalesce_identical_request() =
        runTest {
            val source = FakeDataSource(delayMillis = 100)
            val repository = HistoricalPriceRepositoryImpl(source, FakeCacheProvider())
            val first = async { repository.observe(RANGE).toList() }
            val second = async { repository.observe(RANGE).toList() }
            runCurrent()
            first.await()
            second.await()

            assertEquals(1, source.requests.size)
        }

    @Test
    fun concurrent_overlapping_ranges_fetch_only_the_incremental_tail() =
        runTest {
            val source = FakeDataSource(delayMillis = 100)
            val repository = HistoricalPriceRepositoryImpl(source, FakeCacheProvider())
            val firstRange = PriceDateRange(DATE_1, DATE_2)
            val secondRange = RANGE
            val first = async { repository.observe(firstRange).toList() }
            val second = async { repository.observe(secondRange).toList() }
            runCurrent()
            first.await()
            second.await()

            assertEquals(
                listOf(firstRange, PriceDateRange(DATE_3, DATE_3)),
                source.requests,
            )
        }

    @Test
    fun complete_empty_interval_is_not_repeatedly_fetched() =
        runTest {
            val source =
                FakeDataSource { range ->
                    PricingResult.Success(
                        DailyPriceSeries(
                            fiatCurrency = FiatCurrency.USD,
                            points = emptyList(),
                            availableFrom = DATE_1.minusYears(1),
                            availableTo = DATE_3,
                            dataAsOf = DATE_3.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                        )
                    )
                }
            val repository = HistoricalPriceRepositoryImpl(source, FakeCacheProvider())
            repository.observe(RANGE).toList()
            repository.observe(RANGE).toList()

            assertEquals(1, source.requests.size)
        }

    @Test
    fun gapped_interval_is_refetched_after_cooldown_and_recovers_when_backend_is_repaired() =
        runTest {
            var returnGap = true
            val source =
                FakeDataSource { range ->
                    val completeSeries = series(range)
                    val returnedSeries =
                        if (returnGap) {
                            returnGap = false
                            completeSeries.copy(points = completeSeries.points.filterNot { it.date == DATE_2 })
                        } else {
                            completeSeries
                        }
                    PricingResult.Success(returnedSeries)
                }
            val cache = FakeCacheProvider()

            val firstStates = HistoricalPriceRepositoryImpl(source, cache).observe(RANGE).toList()

            assertTrue((firstStates.last() as HistoricalPriceState.Data).isStale)
            assertEquals(
                listOf(
                    CachedDateRange(DATE_1.toString(), DATE_1.toString()),
                    CachedDateRange(DATE_3.toString(), DATE_3.toString()),
                ),
                cache.value?.completedRanges,
            )

            cache.value = cache.value?.copy(refreshNotBefore = Instant.EPOCH.toString())
            val repairedStates = HistoricalPriceRepositoryImpl(source, cache).observe(RANGE).toList()

            assertEquals(listOf(RANGE, PriceDateRange(DATE_2, DATE_2)), source.requests)
            val repaired = repairedStates.last() as HistoricalPriceState.Data
            assertTrue(!repaired.isStale)
            assertEquals(listOf(DATE_1, DATE_2, DATE_3), repaired.series.points.map(DailyFiatPrice::date))
        }

    @Test
    fun unavailable_trailing_dates_use_persisted_cooldown_instead_of_permanent_coverage() =
        runTest {
            val source =
                FakeDataSource {
                    PricingResult.Success(
                        series(PriceDateRange(DATE_1, DATE_2)).copy(availableTo = DATE_2)
                    )
                }
            val cache = FakeCacheProvider()
            val firstStates = HistoricalPriceRepositoryImpl(source, cache).observe(RANGE).toList()
            val secondStates = HistoricalPriceRepositoryImpl(source, cache).observe(RANGE).toList()

            assertEquals(1, source.requests.size)
            assertTrue((firstStates.last() as HistoricalPriceState.Data).isStale)
            assertTrue((secondStates.single() as HistoricalPriceState.Data).isStale)
            assertEquals(
                listOf(CachedDateRange(DATE_1.toString(), DATE_2.toString())),
                cache.value?.completedRanges,
            )
            assertTrue(cache.value?.refreshNotBefore != null)
        }

    @Test
    fun legacy_coverage_beyond_available_to_is_clamped_and_refetched() =
        runTest {
            val staleCache =
                cache(RANGE).copy(
                    availableTo = DATE_2.toString(),
                    dataAsOf = DATE_2.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toString(),
                )
            val source = FakeDataSource()
            HistoricalPriceRepositoryImpl(source, FakeCacheProvider(staleCache)).observe(RANGE).toList()

            assertEquals(listOf(PriceDateRange(DATE_3, DATE_3)), source.requests)
        }

    @Test
    fun offline_refresh_retains_stale_cached_data() =
        runTest {
            val covered = PriceDateRange(DATE_1, DATE_2)
            val requested = RANGE
            val source = FakeDataSource { PricingResult.Failure(PricingFailure.Network(IOException("offline"))) }
            val repository = HistoricalPriceRepositoryImpl(source, FakeCacheProvider(cache(covered)))
            val states =
                repository.observe(requested).toList()

            assertTrue(states.first() is HistoricalPriceState.Data)
            assertTrue(states.none { it is HistoricalPriceState.Unavailable })
            repository.observe(requested).toList()
            assertEquals(1, source.requests.size)
        }

    @Test
    fun currency_change_uses_a_separate_cache_and_request() =
        runTest {
            val inr = FiatCurrency("INR")
            val source = FakeDataSource()
            val cache = FakeCacheProvider()
            val repository = HistoricalPriceRepositoryImpl(source, cache)

            repository.observe(RANGE, FiatCurrency.USD).toList()
            repository.observe(RANGE, inr).toList()
            repository.observe(RANGE, FiatCurrency.USD).toList()

            assertEquals(listOf(FiatCurrency.USD, inr), source.requestedCurrencies)
            assertEquals(setOf(FiatCurrency.USD, inr), cache.storedCurrencies)
        }

    @Test
    fun mismatched_response_currency_is_rejected() =
        runTest {
            val inr = FiatCurrency("INR")
            val source = FakeDataSource(normalizeSuccessCurrency = false)
            val states = HistoricalPriceRepositoryImpl(source, FakeCacheProvider()).observe(RANGE, inr).toList()

            val unavailable = states.last() as HistoricalPriceState.Unavailable
            assertEquals(
                PricingFailure.InvalidResponse("series fiat currency changed"),
                unavailable.failure,
            )
        }

    @Test
    fun series_unavailable_is_suppressed_for_the_rest_of_utc_day() =
        runTest {
            val source = FakeDataSource { PricingResult.Failure(PricingFailure.SeriesUnavailable) }
            val repository = HistoricalPriceRepositoryImpl(source, FakeCacheProvider())
            repository.observe(RANGE).toList()
            repository.observe(RANGE).toList()

            assertEquals(1, source.requests.size)
        }

    @Test
    fun disk_cache_failure_keeps_the_successful_snapshot_in_memory() =
        runTest {
            val source = FakeDataSource()
            val repository = HistoricalPriceRepositoryImpl(source, FakeCacheProvider(failStore = true))

            val first = repository.observe(RANGE).toList()
            val second = repository.observe(RANGE).toList()

            assertTrue(first.last() is HistoricalPriceState.Data)
            assertTrue(second.single() is HistoricalPriceState.Data)
            assertEquals(1, source.requests.size)
        }

    private fun cache(range: PriceDateRange): HistoricalPriceCache {
        val series = series(range)
        return HistoricalPriceCache(
            fiatCurrencyCode = series.fiatCurrency.code,
            availableFrom = series.availableFrom.toString(),
            availableTo = series.availableTo.toString(),
            dataAsOf = series.dataAsOf.toString(),
            points = series.points.map { CachedDailyPrice(it.date.toString(), it.fiatPerZec.toPlainString()) },
            completedRanges = listOf(CachedDateRange(range.from.toString(), range.to.toString())),
            lastCompletedRequestDate = range.to.toString(),
        )
    }

    private fun series(range: PriceDateRange): DailyPriceSeries {
        val points = mutableListOf<DailyFiatPrice>()
        var date = range.from
        while (!date.isAfter(range.to)) {
            points += DailyFiatPrice(date, BigDecimal("25"))
            date = date.plusDays(1)
        }
        return DailyPriceSeries(
            fiatCurrency = FiatCurrency.USD,
            points = points,
            availableFrom = DATE_1.minusYears(1),
            availableTo = range.to,
            dataAsOf = range.to.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
        )
    }

    private inner class FakeDataSource(
        private val delayMillis: Long = 0,
        private val normalizeSuccessCurrency: Boolean = true,
        private val result: (PriceDateRange) -> PricingResult<DailyPriceSeries> = { PricingResult.Success(series(it)) },
    ) : PricingEngineDataSource {
        val requests = mutableListOf<PriceDateRange>()
        val requestedCurrencies = mutableListOf<FiatCurrency>()

        override suspend fun getDailyPrices(
            range: PriceDateRange,
            fiatCurrency: FiatCurrency,
        ): PricingResult<DailyPriceSeries> {
            requests += range
            requestedCurrencies += fiatCurrency
            if (delayMillis > 0) delay(delayMillis)
            return when (val value = result(range)) {
                is PricingResult.Success -> {
                    if (normalizeSuccessCurrency) {
                        PricingResult.Success(value.value.copy(fiatCurrency = fiatCurrency))
                    } else {
                        value
                    }
                }

                is PricingResult.Failure -> {
                    value
                }
            }
        }
    }

    private class FakeCacheProvider(
        initial: HistoricalPriceCache? = null,
        private val failStore: Boolean = false,
    ) : HistoricalPriceCacheProvider {
        private val values = mutableMapOf<FiatCurrency, HistoricalPriceCache>()
        val storedCurrencies: Set<FiatCurrency>
            get() = values.keys

        var value: HistoricalPriceCache?
            get() = values[FiatCurrency.USD]
            set(newValue) {
                if (newValue == null) {
                    values.remove(FiatCurrency.USD)
                } else {
                    values[FiatCurrency.USD] = newValue
                }
            }

        init {
            value = initial
        }

        override suspend fun load(fiatCurrency: FiatCurrency): HistoricalPriceCache? = values[fiatCurrency]

        override suspend fun store(
            fiatCurrency: FiatCurrency,
            cache: HistoricalPriceCache,
        ) {
            if (failStore) throw IOException("disk full")
            values[fiatCurrency] = cache
        }
    }

    private fun HistoricalPriceRepositoryImpl.observe(range: PriceDateRange) = observe(range, FiatCurrency.USD)

    private companion object {
        val DATE_1: LocalDate = LocalDate.parse("2026-08-01")
        val DATE_2: LocalDate = LocalDate.parse("2026-08-02")
        val DATE_3: LocalDate = LocalDate.parse("2026-08-03")
        val RANGE = PriceDateRange(DATE_1, DATE_3)
    }
}
