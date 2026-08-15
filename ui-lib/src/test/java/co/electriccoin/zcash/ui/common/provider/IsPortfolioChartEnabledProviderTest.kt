package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IsPortfolioChartEnabledProviderTest {
    @Test
    fun default_is_on_and_stored_value_survives_provider_recreation() =
        runTest {
            var stored: String? = null
            val changes = MutableStateFlow<String?>(null)
            val preferences = mockk<PreferenceProvider>()
            val key = PreferenceKey("USD_PORTFOLIO_CHART_ENABLED")
            coEvery { preferences.getString(key) } answers { stored }
            coEvery { preferences.putString(key, any()) } answers {
                stored = secondArg()
                changes.value = stored
            }
            every { preferences.observe(key) } returns changes
            val holder = mockk<StandardPreferenceProvider>()
            coEvery { holder.invoke() } returns preferences

            assertTrue(IsPortfolioChartEnabledProviderImpl(holder).get())
            IsPortfolioChartEnabledProviderImpl(holder).store(false)
            assertFalse(IsPortfolioChartEnabledProviderImpl(holder).get())
        }
}
