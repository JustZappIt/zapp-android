package co.electriccoin.zcash.ui.common.pricing.usecase

import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.common.pricing.model.PriceDateRange
import co.electriccoin.zcash.ui.common.pricing.repository.HistoricalPriceRepository
import co.electriccoin.zcash.ui.common.provider.IsPortfolioChartEnabledProvider
import co.electriccoin.zcash.ui.common.provider.PreferredFiatProvider
import kotlinx.coroutines.flow.collect
import java.time.Instant

/** Warms only the default chart window while the user is on the initial Chats tab. */
class PrewarmPortfolioHistoryUseCase(
    private val repository: HistoricalPriceRepository,
    private val preferredFiatProvider: PreferredFiatProvider,
    private val isPortfolioChartEnabledProvider: IsPortfolioChartEnabledProvider,
) {
    suspend operator fun invoke(now: Instant = Instant.now()) {
        if (!isPortfolioChartEnabledProvider.get()) return

        val completedDate = latestCompletedUtcDate(now)
        val range = PriceDateRange(completedDate.minusDays(DEFAULT_RANGE_START_OFFSET_DAYS), completedDate)
        val fiatCurrency = preferredFiatProvider.get() ?: FiatCurrency.USD

        // Collect to completion: a stale cached emission can be followed by a missing-day refresh.
        repository.observe(range, fiatCurrency).collect()
    }
}

private const val DEFAULT_RANGE_START_OFFSET_DAYS = 7L
