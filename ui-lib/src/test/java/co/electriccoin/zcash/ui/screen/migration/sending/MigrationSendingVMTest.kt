package co.electriccoin.zcash.ui.screen.migration.sending

import androidx.navigation.NavBackStackEntry
import cash.z.ecc.android.sdk.MigrationProgress
import cash.z.ecc.android.sdk.NetworkPrivacyOptions
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.TransferResult
import co.electriccoin.zcash.ui.BaseNavigationCommand
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.common.repository.PendingMigrationTorFailureDecisionRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.ScheduleNextMigrationWindowUseCase
import co.electriccoin.zcash.ui.screen.migration.success.MigrationSuccessArgs
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MigrationSendingVMTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun invalidNoteShowsRetryableFailureSheetWithMappedMessage() = runTest {
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
        coEvery { sdk.finalizeReadyTransfers() } returns 0
        coEvery { sdk.executeNextPendingTransfer(any()) } returns TransferResult.InvalidNote
        val router = FakeNavigationRouter()
        val vm = vm(sdk = sdk, router = router)
        val collectJob = launch { vm.state.collect {} }

        advanceUntilIdle()

        val sheet = vm.state.value.content?.failureSheet
        collectJob.cancel()
        assertEquals(
            "This transfer's note was already spent elsewhere. Reschedule to plan a new one.",
            sheet?.message,
        )
        assertTrue(sheet != null)
    }

    @Test
    fun persistentNullExecuteResultRetriesThenShowsNotReadySheet() = runTest {
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
        coEvery { sdk.finalizeReadyTransfers() } returns 0
        coEvery { sdk.executeNextPendingTransfer(any()) } returns null
        val router = FakeNavigationRouter()
        val vm = vm(sdk = sdk, router = router)
        val collectJob = launch { vm.state.collect {} }

        advanceUntilIdle()

        io.mockk.coVerify(exactly = 3) { sdk.executeNextPendingTransfer(any()) }
        io.mockk.coVerify(exactly = 3) { sdk.finalizeReadyTransfers() }
        assertEquals(
            "This transfer isn't ready to send yet. Please try again in a moment.",
            vm.state.value.content?.failureSheet?.message,
        )
        collectJob.cancel()
    }

    @Test
    fun finalizeReadyTransfersIsCalledBeforeExecutingOnEverySuccessfulAttempt() = runTest {
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
        coEvery { sdk.finalizeReadyTransfers() } returns 1
        coEvery { sdk.executeNextPendingTransfer(any()) } returns TransferResult.Success("txid123")
        val router = FakeNavigationRouter()
        val plans = mockk<MigrationPlanRepository> {
            coEvery { load() } returns null
        }
        vm(sdk = sdk, router = router, plans = plans)

        advanceUntilIdle()

        io.mockk.coVerifyOrder {
            sdk.finalizeReadyTransfers()
            sdk.executeNextPendingTransfer(any())
        }
        assertEquals<List<Any>>(listOf(MigrationSuccessArgs("txid123")), router.forwardedRoutes)
    }

    @Test
    fun sendIsTriggeredAutomaticallyOnConstructionWithoutAnExternalCall() = runTest {
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
        coEvery { sdk.finalizeReadyTransfers() } returns 0
        coEvery { sdk.executeNextPendingTransfer(any()) } returns TransferResult.Success("txid456")
        val router = FakeNavigationRouter()
        val plans = mockk<MigrationPlanRepository> { coEvery { load() } returns null }

        // No call to vm.send() anywhere in this test — construction alone must trigger it.
        vm(sdk = sdk, router = router, plans = plans)
        advanceUntilIdle()

        io.mockk.coVerify(exactly = 1) { sdk.executeNextPendingTransfer(any()) }
    }

    @Test
    fun onBackNavigatesBackImmediately() = runTest {
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
        coEvery { sdk.finalizeReadyTransfers() } returns 0
        coEvery { sdk.executeNextPendingTransfer(any()) } returns null
        val router = FakeNavigationRouter()
        val vm = vm(sdk = sdk, router = router)

        vm.onBack()

        assertEquals(1, router.backCount)
    }

    @Test
    fun pendingImmediateProposalIsSubmittedInsteadOfCallingTheEngine() = runTest {
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
        val proposal = mockk<cash.z.ecc.android.sdk.model.Proposal>()
        val usk = mockk<cash.z.ecc.android.sdk.model.UnifiedSpendingKey>()
        val pendingProposalRepository = mockk<co.electriccoin.zcash.ui.common.repository.PendingImmediateProposalRepository> {
            io.mockk.every { get() } returns proposal
            io.mockk.every { clear() } returns Unit
        }
        val proposalDataSource = mockk<co.electriccoin.zcash.ui.common.datasource.ProposalDataSource> {
            coEvery {
                submitTransaction(proposal, usk)
            } returns co.electriccoin.zcash.ui.common.model.SubmitResult.Success(listOf("tx789"))
        }
        val spendingKeyDataSource = mockk<co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource> {
            coEvery { getZashiSpendingKey() } returns usk
        }
        val router = FakeNavigationRouter()

        vm(
            sdk = sdk,
            router = router,
            proposalDataSource = proposalDataSource,
            pendingImmediateProposalRepository = pendingProposalRepository,
            zashiSpendingKeyDataSource = spendingKeyDataSource,
        )
        advanceUntilIdle()

        io.mockk.coVerify(exactly = 0) { sdk.executeNextPendingTransfer(any()) }
        io.mockk.coVerify(exactly = 1) { pendingProposalRepository.clear() }
        assertEquals<List<Any>>(listOf(MigrationSuccessArgs("tx789")), router.forwardedRoutes)
    }

    @Test
    fun pendingImmediateProposalGrpcFailureIsRetryableAndKeepsTheProposal() = runTest {
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
        val proposal = mockk<cash.z.ecc.android.sdk.model.Proposal>()
        val usk = mockk<cash.z.ecc.android.sdk.model.UnifiedSpendingKey>()
        val pendingProposalRepository = mockk<co.electriccoin.zcash.ui.common.repository.PendingImmediateProposalRepository> {
            io.mockk.every { get() } returns proposal
            io.mockk.every { clear() } returns Unit
        }
        val proposalDataSource = mockk<co.electriccoin.zcash.ui.common.datasource.ProposalDataSource> {
            coEvery {
                submitTransaction(proposal, usk)
            } returns co.electriccoin.zcash.ui.common.model.SubmitResult.GrpcFailure(listOf())
        }
        val spendingKeyDataSource = mockk<co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource> {
            coEvery { getZashiSpendingKey() } returns usk
        }
        val router = FakeNavigationRouter()

        val vm = vm(
            sdk = sdk,
            router = router,
            proposalDataSource = proposalDataSource,
            pendingImmediateProposalRepository = pendingProposalRepository,
            zashiSpendingKeyDataSource = spendingKeyDataSource,
        )
        val collectJob = launch { vm.state.collect {} }
        advanceUntilIdle()

        io.mockk.coVerify(exactly = 0) { pendingProposalRepository.clear() }
        val sheet = vm.state.value.content?.failureSheet
        collectJob.cancel()
        assertEquals("Couldn't reach the network. Check your connection and try again.", sheet?.message)
    }

    private fun vm(
        sdk: OrchardMigrationSdk,
        router: FakeNavigationRouter,
        plans: MigrationPlanRepository = mockk(relaxed = true),
        proposalDataSource: co.electriccoin.zcash.ui.common.datasource.ProposalDataSource = mockk(relaxed = true),
        pendingImmediateProposalRepository: co.electriccoin.zcash.ui.common.repository.PendingImmediateProposalRepository =
            mockk {
                io.mockk.every { get() } returns null
            },
        zashiSpendingKeyDataSource: co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource =
            mockk(relaxed = true),
    ) = MigrationSendingVM(
        getOrchardMigrationSdk = mockk<GetOrchardMigrationSdkUseCase> {
            coEvery { this@mockk() } returns sdk
        },
        migrationPlanRepository = plans,
        scheduleNextMigrationWindow = mockk<ScheduleNextMigrationWindowUseCase>(relaxed = true),
        navigationRouter = router,
        errorStateMapper = mockk<ErrorMapperUseCase>(relaxed = true),
        isTorEnabledStorageProvider = mockk<IsTorEnabledStorageProvider> {
            coEvery { get() } returns false
        },
        pendingMigrationTorFailureDecisionRepository = mockk<PendingMigrationTorFailureDecisionRepository> {
            io.mockk.every { decision } returns MutableStateFlow(null)
        },
        proposalDataSource = proposalDataSource,
        pendingImmediateProposalRepository = pendingImmediateProposalRepository,
        zashiSpendingKeyDataSource = zashiSpendingKeyDataSource,
    )

    private class FakeNavigationRouter : NavigationRouter {
        val forwardedRoutes = mutableListOf<Any>()
        var backCount = 0

        override fun forward(vararg routes: Any) { forwardedRoutes.addAll(routes.toList()) }
        override fun replace(vararg routes: Any) = Unit
        override fun replaceAll(vararg routes: Any) = Unit
        override fun back() { backCount++ }
        override fun backTo(route: KClass<*>) = Unit
        override fun custom(block: (NavBackStackEntry?) -> NavigationCommand?) = Unit
        override fun backToRoot() = Unit
        override fun observePipeline(): Flow<BaseNavigationCommand> = emptyFlow()
    }
}
