package co.electriccoin.zcash.ui.common.pricing.usecase

import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.pricing.model.DailyPriceSeries
import co.electriccoin.zcash.ui.common.pricing.model.PriceDateRange
import co.electriccoin.zcash.ui.common.pricing.repository.HistoricalPriceRepository
import co.electriccoin.zcash.ui.common.pricing.repository.HistoricalPriceState
import co.electriccoin.zcash.ui.common.provider.PreferredFiatProvider
import co.electriccoin.zcash.ui.common.usecase.BalanceHistory
import co.electriccoin.zcash.ui.design.component.chart.SparkChartData
import co.electriccoin.zcash.ui.screen.home.balancechart.BalanceChartPeriod
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class ObservePortfolioHistoryUseCase(
    private val repository: HistoricalPriceRepository,
    private val preferredFiatProvider: PreferredFiatProvider,
) {
    fun observe(
        balanceHistory: Flow<BalanceHistory?>,
        period: BalanceChartPeriod,
        now: Instant = Instant.now(),
    ): Flow<PortfolioHistoryState> {
        val completedDate = latestCompletedUtcDate(now)
        return preferredFiatProvider
            .observe()
            .map { it ?: FiatCurrency.USD }
            .distinctUntilChanged()
            .flatMapLatest { fiatCurrency ->
                val initialRange = standardizedRange(period, completedDate, availableFrom = null)
                val prices =
                    repository.observe(initialRange, fiatCurrency).flatMapLatest { initialState ->
                        if (period == BalanceChartPeriod.ALL && initialState is HistoricalPriceState.Data) {
                            val fullRange = standardizedRange(period, completedDate, initialState.series.availableFrom)
                            repository.observe(fullRange, fiatCurrency)
                        } else {
                            flowOf(initialState)
                        }
                    }
                combine(prices, balanceHistory) { priceState, history ->
                    if (history is BalanceHistory.Reconciled) {
                        mapState(priceState, history, now)
                    } else {
                        PortfolioHistoryState.Unavailable
                    }
                }
            }
    }
}

sealed interface PortfolioHistoryState {
    data object Loading : PortfolioHistoryState

    data object Empty : PortfolioHistoryState

    data object Unavailable : PortfolioHistoryState

    data class Data(
        val fiatCurrency: FiatCurrency,
        val chart: SparkChartData,
        val absoluteChangeFiat: BigDecimal,
        val percentageChange: BigDecimal,
        val availableFrom: LocalDate,
        val dataAsOf: Instant,
        val isStale: Boolean,
    ) : PortfolioHistoryState
}

private fun mapState(
    state: HistoricalPriceState,
    balanceHistory: BalanceHistory.Reconciled,
    now: Instant,
): PortfolioHistoryState =
    when (state) {
        HistoricalPriceState.Loading -> {
            PortfolioHistoryState.Loading
        }

        is HistoricalPriceState.Unavailable -> {
            PortfolioHistoryState.Unavailable
        }

        is HistoricalPriceState.Data -> {
            when (val mapped = mapPortfolioHistory(balanceHistory, state.series, now)) {
                PortfolioMappingResult.Empty -> {
                    PortfolioHistoryState.Empty
                }

                PortfolioMappingResult.Unavailable -> {
                    PortfolioHistoryState.Unavailable
                }

                is PortfolioMappingResult.Data -> {
                    PortfolioHistoryState.Data(
                        fiatCurrency = state.series.fiatCurrency,
                        chart = mapped.chart,
                        absoluteChangeFiat = mapped.absoluteChangeFiat,
                        percentageChange = mapped.percentageChange,
                        availableFrom = state.series.availableFrom,
                        dataAsOf = state.series.dataAsOf,
                        isStale = state.isStale,
                    )
                }
            }
        }
    }

internal sealed interface PortfolioMappingResult {
    data object Empty : PortfolioMappingResult

    data object Unavailable : PortfolioMappingResult

    data class Data(
        val chart: SparkChartData,
        val absoluteChangeFiat: BigDecimal,
        val percentageChange: BigDecimal,
    ) : PortfolioMappingResult
}

@Suppress("ReturnCount")
internal fun mapPortfolioHistory(
    balanceHistory: BalanceHistory.Reconciled,
    priceSeries: DailyPriceSeries,
    now: Instant,
): PortfolioMappingResult {
    val prices = priceSeries.points.sortedBy { it.date }
    if (prices.size < MIN_USABLE_PRICE_POINTS) return PortfolioMappingResult.Empty
    if (prices.zipWithNext().any { (first, second) -> second.date != first.date.plusDays(1) }) {
        return PortfolioMappingResult.Unavailable
    }

    val balancePoints = balanceHistory.points.sortedBy { it.timestamp }
    val firstPositive = balancePoints.firstOrNull { it.balance.value > 0L } ?: return PortfolioMappingResult.Empty
    val lastPriceExclusive =
        prices
            .last()
            .date
            .plusDays(1)
            .atStartOfDay()
            .toInstant(ZoneOffset.UTC)
    if (!firstPositive.timestamp.isBefore(lastPriceExclusive)) return PortfolioMappingResult.Unavailable

    var balanceIndex = 0
    var effectiveBalance = Zatoshi(0L)
    val valued = ArrayList<Pair<Instant, BigDecimal>>(prices.size + 1)
    prices.forEach { price ->
        val timestamp = price.date.atStartOfDay().toInstant(ZoneOffset.UTC)
        val nextDay =
            price.date
                .plusDays(1)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC)
        while (balanceIndex < balancePoints.size && balancePoints[balanceIndex].timestamp.isBefore(nextDay)) {
            effectiveBalance = balancePoints[balanceIndex].balance
            balanceIndex++
        }
        val value = effectiveBalance.toZec().multiply(price.fiatPerZec, MathContext.DECIMAL128)
        if (value.signum() > 0 || valued.isNotEmpty()) valued += timestamp to value
    }
    if (valued.size < MIN_USABLE_PRICE_POINTS) return PortfolioMappingResult.Empty

    val latestPrice = prices.last().fiatPerZec
    val currentValue = balanceHistory.confirmedBalance.toZec().multiply(latestPrice, MathContext.DECIMAL128)
    val finalTimestamp = maxOf(now, valued.last().first.plusSeconds(1))
    valued += finalTimestamp to currentValue

    val firstValue = valued.first().second
    if (firstValue.signum() <= 0) return PortfolioMappingResult.Empty
    val absoluteChange = currentValue.subtract(firstValue, MathContext.DECIMAL128)
    val percentageChange =
        absoluteChange
            .divide(firstValue, MathContext.DECIMAL128)
            .multiply(PERCENT_MULTIPLIER, MathContext.DECIMAL128)
    return PortfolioMappingResult.Data(
        chart =
            SparkChartData(
                points =
                    valued.map { (timestamp, value) ->
                        SparkChartData.Point(timestamp.epochSecond.toDouble(), value.toDouble())
                    }
            ),
        absoluteChangeFiat = absoluteChange,
        percentageChange = percentageChange,
    )
}

internal fun latestCompletedUtcDate(now: Instant): LocalDate {
    val utc = now.atZone(ZoneOffset.UTC)
    val daysBack = if (utc.toLocalTime().isBefore(POST_MIDNIGHT_GRACE)) GRACE_DAYS_BACK else NORMAL_DAYS_BACK
    return utc.toLocalDate().minusDays(daysBack)
}

private fun standardizedRange(
    period: BalanceChartPeriod,
    completedDate: LocalDate,
    availableFrom: LocalDate?,
): PriceDateRange =
    when (period) {
        BalanceChartPeriod.W1 -> {
            PriceDateRange(completedDate.minusDays(WEEK_START_OFFSET_DAYS), completedDate)
        }

        BalanceChartPeriod.M1 -> {
            PriceDateRange(completedDate.minusDays(MONTH_START_OFFSET_DAYS), completedDate)
        }

        BalanceChartPeriod.Y1 -> {
            PriceDateRange(completedDate.minusDays(YEAR_START_OFFSET_DAYS), completedDate)
        }

        BalanceChartPeriod.ALL -> {
            PriceDateRange(availableFrom ?: completedDate.minusDays(WEEK_START_OFFSET_DAYS), completedDate)
        }
    }

private fun Zatoshi.toZec(): BigDecimal = BigDecimal(value).divide(ZATOSHI_PER_ZEC, MathContext.DECIMAL128)

private val ZATOSHI_PER_ZEC = BigDecimal("100000000")
private val PERCENT_MULTIPLIER = BigDecimal("100")
private val POST_MIDNIGHT_GRACE = LocalTime.of(MIDNIGHT_HOUR, POST_MIDNIGHT_GRACE_MINUTE)
private const val MIN_USABLE_PRICE_POINTS = 2
private const val NORMAL_DAYS_BACK = 1L
private const val GRACE_DAYS_BACK = 2L
private const val WEEK_START_OFFSET_DAYS = 7L
private const val MONTH_START_OFFSET_DAYS = 30L
private const val YEAR_START_OFFSET_DAYS = 365L
private const val MIDNIGHT_HOUR = 0
private const val POST_MIDNIGHT_GRACE_MINUTE = 10
