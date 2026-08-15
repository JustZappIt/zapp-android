package co.electriccoin.zcash.ui.common.pricing.usecase

import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.common.pricing.model.PriceDateRange
import co.electriccoin.zcash.ui.common.pricing.repository.HistoricalPriceRepository
import co.electriccoin.zcash.ui.common.provider.IsPortfolioChartEnabledProvider
import co.electriccoin.zcash.ui.common.provider.PreferredFiatProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class PrewarmPortfolioHistoryUseCaseTest {
    @Test
    fun prewarms_only_the_default_week_for_the_selected_currency() =
        runTest {
            val repository = mockk<HistoricalPriceRepository>()
            val preferredFiat = mockk<PreferredFiatProvider>()
            val enabled = mockk<IsPortfolioChartEnabledProvider>()
            val jpy = FiatCurrency("JPY")
            coEvery { enabled.get() } returns true
            coEvery { preferredFiat.get() } returns jpy
            every { repository.observe(any(), any()) } returns emptyFlow()

            PrewarmPortfolioHistoryUseCase(repository, preferredFiat, enabled)(NOW)

            verify(exactly = 1) { repository.observe(EXPECTED_RANGE, jpy) }
        }

    @Test
    fun disabled_fiat_chart_makes_no_price_request() =
        runTest {
            val repository = mockk<HistoricalPriceRepository>()
            val preferredFiat = mockk<PreferredFiatProvider>()
            val enabled = mockk<IsPortfolioChartEnabledProvider>()
            coEvery { enabled.get() } returns false

            PrewarmPortfolioHistoryUseCase(repository, preferredFiat, enabled)(NOW)

            coVerify(exactly = 0) { preferredFiat.get() }
            verify(exactly = 0) { repository.observe(any(), any()) }
        }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-12T12:00:00Z")
        val EXPECTED_RANGE = PriceDateRange(LocalDate.parse("2026-08-04"), LocalDate.parse("2026-08-11"))
    }
}
