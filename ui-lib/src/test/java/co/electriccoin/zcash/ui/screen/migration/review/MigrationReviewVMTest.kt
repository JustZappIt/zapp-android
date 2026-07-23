package co.electriccoin.zcash.ui.screen.migration.review

import androidx.navigation.NavBackStackEntry
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.BaseNavigationCommand
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.PendingImmediateProposalRepository
import co.electriccoin.zcash.ui.common.repository.PendingMigrationScheduleRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.FinalizeMigrationScheduleUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardBalanceUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.screen.migration.sending.MigrationSendingArgs
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class MigrationReviewVMTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun immediateConfirmStoresProposalAndNavigatesToSending() = runTest {
        val proposal = mockk<Proposal> {
            coEvery { totalFeeRequired() } returns Zatoshi(1_000L)
        }
        val pendingProposalRepository = mockk<PendingImmediateProposalRepository>(relaxed = true)
        val router = FakeNavigationRouter()
        val vm = MigrationReviewVM(
            args = MigrationReviewArgs(mode = MigrationMode.IMMEDIATE),
            getOrchardMigrationSdk = mockk<GetOrchardMigrationSdkUseCase>(relaxed = true),
            pendingMigrationScheduleRepository = mockk<PendingMigrationScheduleRepository>(relaxed = true),
            finalizeMigrationSchedule = mockk<FinalizeMigrationScheduleUseCase>(relaxed = true),
            navigationRouter = router,
            exchangeRateRepository = mockk<ExchangeRateRepository>(relaxed = true),
            getSelectedWalletAccount = mockk<GetSelectedWalletAccountUseCase> {
                coEvery { this@mockk() } returns mockk<ZashiAccount>(relaxed = true)
                every { observe() } returns flowOf(mockk<ZashiAccount>(relaxed = true))
            },
            getOrchardBalance = mockk<GetOrchardBalanceUseCase> { coEvery { this@mockk() } returns Zatoshi(500_000L) },
            errorStateMapper = mockk<ErrorMapperUseCase>(relaxed = true),
            zashiSpendingKeyDataSource = mockk<ZashiSpendingKeyDataSource> {
                coEvery { getZashiSpendingKey() } returns mockk<UnifiedSpendingKey>()
            },
            biometricRepository = mockk<BiometricRepository>(relaxed = true),
            pendingImmediateProposalRepository = pendingProposalRepository,
        )

        invokeOnConfirmImmediate(vm, proposal)
        advanceUntilIdle()

        coVerifyOrder {
            pendingProposalRepository.set(proposal)
        }
        assertEquals(listOf<Any>(MigrationSendingArgs), router.forwardedRoutes)
    }

    // MigrationReviewVM's IMMEDIATE-mode `onConfirm` callback only becomes reachable through
    // `state.value.content`, which is backed by a `combine(...).stateIn(WhileSubscribed)` chain
    // that (by design, same as every other LCE-driven VM in this codebase, e.g.
    // MigrationCompleteVM) only starts computing once actively collected — exercising it through a
    // real subscriber is exactly the kind of Flow-timing plumbing this test isn't meant to be
    // about. `MigrationCompleteVMTest.invokeOnDone` establishes the same
    // call-the-private-handler-via-reflection pattern for the identical reason.
    private fun invokeOnConfirmImmediate(vm: MigrationReviewVM, proposal: Proposal) {
        val method = MigrationReviewVM::class.java.getDeclaredMethod("onConfirmImmediate", Proposal::class.java)
        method.isAccessible = true
        method.invoke(vm, proposal)
    }

    private class FakeNavigationRouter : NavigationRouter {
        val forwardedRoutes = mutableListOf<Any>()

        override fun forward(vararg routes: Any) { forwardedRoutes.addAll(routes) }
        override fun replace(vararg routes: Any) = Unit
        override fun replaceAll(vararg routes: Any) = Unit
        override fun back() = Unit
        override fun backTo(route: KClass<*>) = Unit
        override fun custom(block: (NavBackStackEntry?) -> NavigationCommand?) = Unit
        override fun backToRoot() = Unit
        override fun observePipeline(): Flow<BaseNavigationCommand> = emptyFlow()
    }
}
