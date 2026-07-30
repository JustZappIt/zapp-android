package co.electriccoin.zcash.ui.screen.migration.scheduled

import androidx.navigation.NavBackStackEntry
import co.electriccoin.zcash.ui.BaseNavigationCommand
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.IsBackgroundExecutionAvailableProvider
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.GetMigrationSnapshotUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class MigrationScheduledVMTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun backgroundNotAvailableShowsHint() =
        runTest {
            val backgroundAvailable =
                mockk<IsBackgroundExecutionAvailableProvider> {
                    every { isAvailable() } returns false
                }
            val vm = vm(isBackgroundExecutionAvailable = backgroundAvailable)

            advanceUntilIdle()
            val state = vm.state.first { !it.isLoading }
            assertNotNull(state.content?.backgroundHint, "backgroundHint should not be null when background execution is unavailable")
        }

    @Test
    fun backgroundAvailableHidesHint() =
        runTest {
            val backgroundAvailable =
                mockk<IsBackgroundExecutionAvailableProvider> {
                    every { isAvailable() } returns true
                }
            val vm = vm(isBackgroundExecutionAvailable = backgroundAvailable)

            advanceUntilIdle()
            val state = vm.state.first { !it.isLoading }
            assertNull(state.content?.backgroundHint, "backgroundHint should be null when background execution is available")
        }

    @Suppress("LongParameterList")
    private fun vm(
        getMigrationSnapshot: GetMigrationSnapshotUseCase =
            mockk {
                // A REAL (empty) snapshot: a null return now means "SDK not ready yet" and keeps
                // the LCE loading (review L3) — these tests assert on the rendered state.
                coEvery { this@mockk(null) } returns
                    co.electriccoin.zcash.ui.common.model.migration
                        .LiveMigrationSnapshot(transfers = emptyList(), preparations = emptyList(), tipHeight = 0L)
            },
        isBackgroundExecutionAvailable: IsBackgroundExecutionAvailableProvider = mockk(relaxed = true),
        navigationRouter: NavigationRouter = FakeNavigationRouter(),
        errorStateMapper: ErrorMapperUseCase = mockk(relaxed = true),
    ) = MigrationScheduledVM(
        getMigrationSnapshot = getMigrationSnapshot,
        navigationRouter = navigationRouter,
        errorStateMapper = errorStateMapper,
        isBackgroundExecutionAvailableProvider = isBackgroundExecutionAvailable,
    )

    private class FakeNavigationRouter : NavigationRouter {
        override fun forward(vararg routes: Any) = Unit

        override fun replace(vararg routes: Any) = Unit

        override fun replaceAll(vararg routes: Any) = Unit

        override fun back() = Unit

        override fun backTo(route: KClass<*>) = Unit

        override fun custom(block: (NavBackStackEntry?) -> NavigationCommand?) = Unit

        override fun backToRoot() = Unit

        override fun observePipeline(): Flow<BaseNavigationCommand> = emptyFlow()
    }
}
