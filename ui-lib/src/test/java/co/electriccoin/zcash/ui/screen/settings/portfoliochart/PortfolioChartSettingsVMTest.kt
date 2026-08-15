package co.electriccoin.zcash.ui.screen.settings.portfoliochart

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.IsPortfolioChartEnabledProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlin.test.AfterTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PortfolioChartSettingsVMTest {
    private val navigationRouter = mockk<NavigationRouter>(relaxed = true)
    private val preference = mockk<IsPortfolioChartEnabledProvider>()

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun toggleIsOnlyPersistedAfterSave() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val storedValue = MutableStateFlow(true)
            every { preference.observe() } returns storedValue
            coEvery { preference.store(any()) } answers { storedValue.value = firstArg() }

            val viewModel = PortfolioChartSettingsVM(navigationRouter, preference)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect() }
            runCurrent()

            assertTrue(viewModel.state.value.isEnabled)
            assertFalse(viewModel.state.value.saveButton.isEnabled)

            viewModel.state.value.onEnabledChange(false)
            runCurrent()

            assertFalse(viewModel.state.value.isEnabled)
            assertTrue(viewModel.state.value.saveButton.isEnabled)
            coVerify(exactly = 0) { preference.store(any()) }

            viewModel.state.value.onEnabledChange(true)
            runCurrent()

            assertTrue(viewModel.state.value.isEnabled)
            assertFalse(viewModel.state.value.saveButton.isEnabled)

            viewModel.state.value.onEnabledChange(false)
            runCurrent()

            viewModel.state.value.saveButton
                .onClick()
            advanceUntilIdle()

            coVerify(exactly = 1) { preference.store(false) }
            verify(exactly = 1) { navigationRouter.back() }
        }

    @Test
    fun saveInProgressBlocksToggleAndBackUntilNavigationAfterStore() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val storedValue = MutableStateFlow(true)
            val storeStarted = CompletableDeferred<Unit>()
            val finishStore = CompletableDeferred<Unit>()
            every { preference.observe() } returns storedValue
            coEvery { preference.store(false) } coAnswers {
                storeStarted.complete(Unit)
                finishStore.await()
                storedValue.value = false
            }

            val viewModel = PortfolioChartSettingsVM(navigationRouter, preference)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect() }
            runCurrent()

            viewModel.state.value.onEnabledChange(false)
            viewModel.state.value.saveButton
                .onClick()
            runCurrent()
            storeStarted.await()

            assertTrue(viewModel.state.value.saveButton.isLoading)
            viewModel.state.value.onEnabledChange(true)
            viewModel.state.value.onBack()
            runCurrent()

            assertFalse(viewModel.state.value.isEnabled)
            verify(exactly = 0) { navigationRouter.back() }

            finishStore.complete(Unit)
            advanceUntilIdle()

            coVerify(exactly = 1) { preference.store(false) }
            verify(exactly = 1) { navigationRouter.back() }
        }

    @Test
    fun backDiscardsUnsavedToggle() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            every { preference.observe() } returns MutableStateFlow(true)

            val viewModel = PortfolioChartSettingsVM(navigationRouter, preference)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect() }
            runCurrent()

            viewModel.state.value.onEnabledChange(false)
            viewModel.state.value.onBack()

            coVerify(exactly = 0) { preference.store(any()) }
            verify(exactly = 1) { navigationRouter.back() }
        }
}
