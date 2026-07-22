package co.electriccoin.zcash.ui.screen.migration.complete

import androidx.navigation.NavBackStackEntry
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.BaseNavigationCommand
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.provider.HasLockedOrchardDustStorageProvider
import co.electriccoin.zcash.ui.common.provider.HasSeenMigrationCompleteStorageProvider
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardBalanceUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MigrationCompleteVMTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun keystoneAccountWithResidualBalanceClearsPlanInsteadOfMarkingSeen() = runTest {
        val plans = mockk<MigrationPlanRepository>(relaxed = true)
        val seen = mockk<HasSeenMigrationCompleteStorageProvider>(relaxed = true)
        val vm = vm(
            plans = plans,
            seen = seen,
            account = mockk<KeystoneAccount>(relaxed = true),
            orchardBalanceZatoshi = 500_000L,
        )

        vm.state.value // force lazy init to run (StateFlow combine below reads loadLce)
        advanceUntilIdle()
        invokeOnDone(vm)
        advanceUntilIdle()

        coVerify(exactly = 1) { plans.clear() }
        coVerify(exactly = 0) { seen.store(true) }
    }

    @Test
    fun keystoneAccountWithZeroResidualBalanceMarksSeenInsteadOfClearing() = runTest {
        val plans = mockk<MigrationPlanRepository>(relaxed = true)
        val seen = mockk<HasSeenMigrationCompleteStorageProvider>(relaxed = true)
        val vm = vm(
            plans = plans,
            seen = seen,
            account = mockk<KeystoneAccount>(relaxed = true),
            orchardBalanceZatoshi = 0L,
        )

        advanceUntilIdle()
        invokeOnDone(vm)
        advanceUntilIdle()

        coVerify(exactly = 0) { plans.clear() }
        coVerify(exactly = 1) { seen.store(true) }
    }

    @Test
    fun nonKeystoneAccountWithResidualBalanceMarksSeenInsteadOfClearing() = runTest {
        // Scope: hot-wallet multi-round continuation is deferred, so a non-Keystone account always
        // takes the terminal path regardless of residual balance.
        val plans = mockk<MigrationPlanRepository>(relaxed = true)
        val seen = mockk<HasSeenMigrationCompleteStorageProvider>(relaxed = true)
        val vm = vm(
            plans = plans,
            seen = seen,
            account = mockk<ZashiAccount>(relaxed = true),
            orchardBalanceZatoshi = 500_000L,
        )

        advanceUntilIdle()
        invokeOnDone(vm)
        advanceUntilIdle()

        coVerify(exactly = 0) { plans.clear() }
        coVerify(exactly = 1) { seen.store(true) }
    }

    private fun invokeOnDone(vm: MigrationCompleteVM) {
        val onDone = MigrationCompleteVM::class.java.getDeclaredMethod("onDone")
        onDone.isAccessible = true
        onDone.invoke(vm)
    }

    private fun vm(
        plans: MigrationPlanRepository,
        seen: HasSeenMigrationCompleteStorageProvider,
        account: WalletAccount,
        orchardBalanceZatoshi: Long,
    ) = MigrationCompleteVM(
        migrationPlanRepository = plans,
        getOrchardBalance = mockk<GetOrchardBalanceUseCase> {
            coEvery { this@mockk() } returns Zatoshi(orchardBalanceZatoshi)
        },
        hasSeenMigrationCompleteStorageProvider = seen,
        hasLockedOrchardDustStorageProvider = mockk<HasLockedOrchardDustStorageProvider>(relaxed = true),
        getSelectedWalletAccount = mockk<GetSelectedWalletAccountUseCase> {
            coEvery { this@mockk() } returns account
        },
        navigationRouter = FakeNavigationRouter(),
        errorStateMapper = mockk<ErrorMapperUseCase>(relaxed = true),
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
