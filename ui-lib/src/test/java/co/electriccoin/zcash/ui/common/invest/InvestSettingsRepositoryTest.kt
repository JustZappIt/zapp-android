package co.electriccoin.zcash.ui.common.invest

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettings
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettingsRepositoryImpl
import co.electriccoin.zcash.ui.screen.grouplink.model.InMemoryPreferenceProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class InvestSettingsRepositoryTest {
    private val prefs = InMemoryPreferenceProvider().apply { put(KEY, "{not json") }
    private val encrypted = mockk<EncryptedPreferenceProvider>().also { coEvery { it.invoke() } returns prefs }
    private val repository = InvestSettingsRepositoryImpl(encrypted)

    @Test
    fun `unreadable settings are cleared and asked again, and later answers still arrive`() =
        runTest {
            val seen = mutableListOf<InvestSettings>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.settings.collect { seen += it }
            }

            // The PAY tab reads these at every launch: unreadable must not crash it.
            assertEquals(listOf(InvestSettings()), seen)
            assertNull(prefs.raw(KEY))

            repository.setResidence("AE", qualifiedInvestor = false)
            assertEquals("AE", seen.last().countryCode)
        }

    @Test
    fun `a one-off read of unreadable settings falls back too`() =
        runTest {
            assertEquals(InvestSettings(), repository.get())
            assertNull(prefs.raw(KEY))
        }

    private companion object {
        const val KEY = "invest_settings_v1"
    }
}
