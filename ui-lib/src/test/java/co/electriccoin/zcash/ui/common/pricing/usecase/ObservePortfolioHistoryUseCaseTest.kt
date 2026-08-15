package co.electriccoin.zcash.ui.common.pricing.usecase

import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.pricing.model.DailyFiatPrice
import co.electriccoin.zcash.ui.common.pricing.model.DailyPriceSeries
import co.electriccoin.zcash.ui.common.pricing.model.PriceDateRange
import co.electriccoin.zcash.ui.common.pricing.repository.HistoricalPriceRepository
import co.electriccoin.zcash.ui.common.pricing.repository.HistoricalPriceState
import co.electriccoin.zcash.ui.common.provider.PreferredFiatProvider
import co.electriccoin.zcash.ui.common.usecase.BalanceHistory
import co.electriccoin.zcash.ui.common.usecase.BalanceHistoryPoint
import co.electriccoin.zcash.ui.screen.home.balancechart.BalanceChartPeriod
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@Suppress("MaxLineLength")
@OptIn(ExperimentalCoroutinesApi::class)
class ObservePortfolioHistoryUseCaseTest {
    @Test
    fun balance_updates_remap_cached_prices_without_restarting_repository() =
        runTest {
            var repositoryRequests = 0
            val repository =
                object : HistoricalPriceRepository {
                    override fun observe(
                        range: PriceDateRange,
                        fiatCurrency: FiatCurrency,
                    ): Flow<HistoricalPriceState> {
                        repositoryRequests++
                        return flowOf(HistoricalPriceState.Data(prices("10", "20", fiat = fiatCurrency), isStale = false))
                    }
                }
            val balances =
                MutableStateFlow<BalanceHistory?>(
                    history(point("2026-07-31T12:00:00Z", 1.zec), confirmed = 1.zec)
                )
            val states = mutableListOf<PortfolioHistoryState>()
            val collection =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    useCase(repository)
                        .observe(balances, BalanceChartPeriod.W1, NOW)
                        .collect(states::add)
                }
            runCurrent()

            balances.value = history(point("2026-07-31T12:00:00Z", 2.zec), confirmed = 2.zec)
            runCurrent()

            assertEquals(1, repositoryRequests)
            val initialValue =
                (states.first() as PortfolioHistoryState.Data)
                    .chart
                    .points
                    .last()
                    .y
            val updatedValue =
                (states.last() as PortfolioHistoryState.Data)
                    .chart
                    .points
                    .last()
                    .y
            assertEquals(20.0, initialValue, 0.0)
            assertEquals(40.0, updatedValue, 0.0)
            collection.cancel()
        }

    @Test
    fun preferred_currency_change_requests_and_emits_that_historical_fiat_series() =
        runTest {
            val inr = FiatCurrency("INR")
            val requestedCurrencies = mutableListOf<FiatCurrency>()
            val repository =
                object : HistoricalPriceRepository {
                    override fun observe(
                        range: PriceDateRange,
                        fiatCurrency: FiatCurrency,
                    ): Flow<HistoricalPriceState> {
                        requestedCurrencies += fiatCurrency
                        val values = if (fiatCurrency == inr) arrayOf("800", "1600") else arrayOf("10", "20")
                        return flowOf(
                            HistoricalPriceState.Data(
                                prices(*values, fiat = fiatCurrency),
                                isStale = false,
                            )
                        )
                    }
                }
            val preferredFiat = MutableStateFlow<FiatCurrency?>(FiatCurrency.USD)
            val states = mutableListOf<PortfolioHistoryState>()
            val balances =
                MutableStateFlow<BalanceHistory?>(
                    history(point("2026-07-31T12:00:00Z", 1.zec), confirmed = 1.zec)
                )
            val collection =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    useCase(repository, preferredFiat)
                        .observe(balances, BalanceChartPeriod.W1, NOW)
                        .collect(states::add)
                }
            runCurrent()

            preferredFiat.value = inr
            runCurrent()

            assertEquals(listOf(FiatCurrency.USD, inr), requestedCurrencies)
            val latest = states.last() as PortfolioHistoryState.Data
            assertEquals(inr, latest.fiatCurrency)
            assertEquals(
                1600.0,
                latest.chart.points
                    .last()
                    .y,
                0.0,
            )
            collection.cancel()
        }

    @Test
    fun buy_and_hold_value_changes_with_price_and_reports_fiat_delta() {
        val result =
            mapPortfolioHistory(
                history(point("2026-07-31T12:00:00Z", 1.zec), confirmed = 1.zec),
                prices("10", "20"),
                NOW,
            ) as PortfolioMappingResult.Data

        assertEquals(listOf(10.0, 20.0, 20.0), result.chart.points.map { it.y })
        assertBigDecimalEquals("10", result.absoluteChangeFiat)
        assertBigDecimalEquals("100", result.percentageChange)
    }

    @Test
    fun deposit_and_withdrawal_between_daily_samples_use_effective_balance() {
        val result =
            mapPortfolioHistory(
                history(
                    point("2026-07-31T12:00:00Z", 1.zec),
                    point("2026-08-01T12:00:00Z", 3.zec),
                    point("2026-08-02T12:00:00Z", 2.zec),
                    confirmed = 2.zec,
                ),
                prices("10", "10", "10"),
                NOW,
            ) as PortfolioMappingResult.Data

        assertEquals(listOf(30.0, 20.0, 20.0, 20.0), result.chart.points.map { it.y })
    }

    @Test
    fun fee_reduced_balance_is_used_without_reconstructing_amounts() {
        val result =
            mapPortfolioHistory(
                history(
                    point("2026-07-31T12:00:00Z", 1.zec),
                    point("2026-08-01T12:00:00Z", 90_000_000L),
                    confirmed = 90_000_000L,
                ),
                prices("10", "10"),
                NOW,
            ) as PortfolioMappingResult.Data

        assertEquals(listOf(9.0, 9.0, 9.0), result.chart.points.map { it.y })
    }

    @Test
    fun balance_before_period_establishes_the_period_baseline() {
        val result =
            mapPortfolioHistory(
                history(point("2020-01-01T00:00:00Z", 2.zec), confirmed = 2.zec),
                prices("5", "6"),
                NOW,
            ) as PortfolioMappingResult.Data

        assertEquals(
            10.0,
            result.chart.points
                .first()
                .y,
            0.0
        )
    }

    @Test
    fun leading_zero_days_are_omitted() {
        val result =
            mapPortfolioHistory(
                history(point("2026-08-01T12:00:00Z", 1.zec), confirmed = 1.zec),
                prices("5", "6", "7"),
                NOW,
            ) as PortfolioMappingResult.Data

        assertEquals(4, result.chart.points.size)
        assertEquals(
            5.0,
            result.chart.points
                .first()
                .y,
            0.0
        )
    }

    @Test
    fun no_wallet_price_overlap_is_unavailable() {
        val result =
            mapPortfolioHistory(
                history(point("2026-08-03T12:00:00Z", 1.zec), confirmed = 1.zec),
                prices("5", "6"),
                NOW,
            )

        assertEquals(PortfolioMappingResult.Unavailable, result)
    }

    @Test
    fun transaction_at_next_midnight_belongs_to_the_next_daily_close() {
        val result =
            mapPortfolioHistory(
                history(
                    point("2026-07-31T12:00:00Z", 1.zec),
                    point("2026-08-02T00:00:00Z", 2.zec),
                    confirmed = 2.zec,
                ),
                prices("10", "10"),
                NOW,
            ) as PortfolioMappingResult.Data

        assertEquals(listOf(10.0, 20.0, 20.0), result.chart.points.map { it.y })
    }

    @Test
    fun missing_daily_price_is_rejected_instead_of_interpolated() {
        val series =
            DailyPriceSeries(
                fiatCurrency = FiatCurrency.USD,
                points =
                    listOf(
                        DailyFiatPrice(LocalDate.parse("2026-08-01"), BigDecimal("5")),
                        DailyFiatPrice(LocalDate.parse("2026-08-03"), BigDecimal("7")),
                    ),
                availableFrom = LocalDate.parse("2026-08-01"),
                availableTo = LocalDate.parse("2026-08-03"),
                dataAsOf = Instant.parse("2026-08-03T00:00:00Z"),
            )

        assertEquals(
            PortfolioMappingResult.Unavailable,
            mapPortfolioHistory(history(point("2026-07-31T00:00:00Z", 1.zec), confirmed = 1.zec), series, NOW),
        )
    }

    @Test
    fun fewer_than_two_prices_is_empty() {
        val onePrice = prices("5").copy(availableTo = LocalDate.parse("2026-08-01"))

        assertEquals(
            PortfolioMappingResult.Empty,
            mapPortfolioHistory(history(point("2026-07-31T00:00:00Z", 1.zec), confirmed = 1.zec), onePrice, NOW),
        )
    }

    @Test
    fun ingestion_grace_does_not_request_a_day_before_its_close_point_is_reliably_available() {
        assertEquals(
            LocalDate.parse("2026-08-09"),
            latestCompletedUtcDate(Instant.parse("2026-08-11T00:05:00Z")),
        )
        assertEquals(
            LocalDate.parse("2026-08-10"),
            latestCompletedUtcDate(Instant.parse("2026-08-11T00:11:00Z")),
        )
    }

    private fun prices(
        vararg values: String,
        fiat: FiatCurrency = FiatCurrency.USD,
    ): DailyPriceSeries {
        val from = LocalDate.parse("2026-08-01")
        val points = values.mapIndexed { index, value -> DailyFiatPrice(from.plusDays(index.toLong()), BigDecimal(value)) }
        return DailyPriceSeries(
            fiatCurrency = fiat,
            points = points,
            availableFrom = from,
            availableTo = from.plusDays(values.lastIndex.toLong()),
            dataAsOf = from.plusDays(values.lastIndex.toLong()).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
        )
    }

    private fun point(timestamp: String, balance: Long) = BalanceHistoryPoint(Instant.parse(timestamp), Zatoshi(balance))

    private fun history(
        vararg points: BalanceHistoryPoint,
        confirmed: Long,
    ) = BalanceHistory.Reconciled(points.toList(), Zatoshi(confirmed))

    private fun assertBigDecimalEquals(expected: String, actual: BigDecimal) {
        assertTrue(BigDecimal(expected).compareTo(actual) == 0)
    }

    private val Int.zec: Long
        get() = this * 100_000_000L

    private fun useCase(
        repository: HistoricalPriceRepository,
        preferredFiat: MutableStateFlow<FiatCurrency?> = MutableStateFlow(FiatCurrency.USD),
    ) = ObservePortfolioHistoryUseCase(repository, FakePreferredFiatProvider(preferredFiat))

    private class FakePreferredFiatProvider(
        private val values: MutableStateFlow<FiatCurrency?>,
    ) : PreferredFiatProvider {
        override suspend fun get(): FiatCurrency? = values.value

        override suspend fun store(amount: FiatCurrency) {
            values.value = amount
        }

        override fun observe(): Flow<FiatCurrency?> = values

        override suspend fun clear() {
            values.value = null
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-04T00:00:00Z")
    }
}
