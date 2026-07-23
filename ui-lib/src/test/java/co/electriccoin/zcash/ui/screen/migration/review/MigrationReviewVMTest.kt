package co.electriccoin.zcash.ui.screen.migration.review

import androidx.navigation.NavBackStackEntry
import cash.z.ecc.android.sdk.MigrationSchedule
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.TransferProposal
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.BaseNavigationCommand
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.PendingMigrationScheduleRepository
import co.electriccoin.zcash.ui.common.repository.RestartMigrationScheduleRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.FinalizeMigrationScheduleUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardBalanceUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.screen.migration.success.MigrationSuccessArgs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
    fun immediateConfirmSubmitsProposalAndNavigatesToSuccessOnSuccess() = runTest {
        val proposal = mockk<Proposal> {
            coEvery { totalFeeRequired() } returns Zatoshi(1_000L)
        }
        val usk = mockk<UnifiedSpendingKey>()
        val router = FakeNavigationRouter()
        val proposalDataSource = mockk<ProposalDataSource> {
            coEvery { submitTransaction(proposal, usk) } returns SubmitResult.Success(listOf("tx1"))
        }
        val vm = vm(
            router = router,
            proposalDataSource = proposalDataSource,
            zashiSpendingKeyDataSource = mockk { coEvery { getZashiSpendingKey() } returns usk },
        )

        invokeOnConfirmImmediate(vm, proposal)
        advanceUntilIdle()

        coVerify(exactly = 1) { proposalDataSource.submitTransaction(proposal, usk) }
        assertEquals(listOf<Any>(MigrationSuccessArgs("tx1")), router.forwardedRoutes)
    }

    @Test
    fun immediateConfirmGrpcFailureShowsRetryableFailureSheetAndRetryResubmits() = runTest {
        val proposal = mockk<Proposal> {
            coEvery { totalFeeRequired() } returns Zatoshi(1_000L)
        }
        val usk = mockk<UnifiedSpendingKey>()
        val router = FakeNavigationRouter()
        val proposalDataSource = mockk<ProposalDataSource> {
            coEvery { submitTransaction(proposal, usk) } returns SubmitResult.GrpcFailure(listOf())
        }
        // The failure sheet's onRetry (built by createImmediateState from the VM's own proposed
        // ReviewProposal.Immediate, not from whatever argument a caller happens to pass to
        // onConfirmImmediate) re-confirms that same proposed Proposal — so proposeImmediateMigration()
        // must return this exact `proposal` instance for the retry-resubmit assertion below to line up.
        val sdk = mockk<cash.z.ecc.android.sdk.OrchardMigrationSdk>(relaxed = true) {
            coEvery { proposeImmediateMigration() } returns proposal
        }
        val vm = vm(
            router = router,
            proposalDataSource = proposalDataSource,
            zashiSpendingKeyDataSource = mockk { coEvery { getZashiSpendingKey() } returns usk },
            getOrchardMigrationSdk = mockk { coEvery { this@mockk() } returns sdk },
        )
        val collectJob = launch { vm.state.collect {} }

        invokeOnConfirmImmediate(vm, proposal)
        advanceUntilIdle()

        val sheet = assertNotNull(vm.state.value.content?.failureSheet)
        assertEquals("Couldn't reach the network. Check your connection and try again.", sheet.message)
        val onRetry = assertNotNull(sheet.onRetry)

        onRetry.invoke()
        advanceUntilIdle()

        collectJob.cancel()
        coVerify(exactly = 2) { proposalDataSource.submitTransaction(proposal, usk) }
    }

    @Test
    fun immediateConfirmNonResubmittableFailureOffersNoRetry() = runTest {
        val proposal = mockk<Proposal> {
            coEvery { totalFeeRequired() } returns Zatoshi(1_000L)
        }
        val usk = mockk<UnifiedSpendingKey>()
        val router = FakeNavigationRouter()
        val proposalDataSource = mockk<ProposalDataSource> {
            coEvery {
                submitTransaction(proposal, usk)
            } returns SubmitResult.Failure(txIds = listOf(), code = -1, description = "rejected")
        }
        val vm = vm(
            router = router,
            proposalDataSource = proposalDataSource,
            zashiSpendingKeyDataSource = mockk { coEvery { getZashiSpendingKey() } returns usk },
        )
        val collectJob = launch { vm.state.collect {} }

        invokeOnConfirmImmediate(vm, proposal)
        advanceUntilIdle()

        val sheet = assertNotNull(vm.state.value.content?.failureSheet)
        collectJob.cancel()
        assertNull(sheet.onRetry)
    }

    @Test
    fun immediateConfirmOnKeystoneAccountAdoptsIntoKeystoneRepositoryAndNavigatesToSign() = runTest {
        val proposal = mockk<Proposal> {
            coEvery { totalFeeRequired() } returns Zatoshi(1_000L)
        }
        val router = FakeNavigationRouter()
        val proposalDataSource = mockk<ProposalDataSource>(relaxed = true)
        val keystoneProposalRepository = mockk<co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository>(
            relaxed = true
        )
        val vm = vm(
            router = router,
            proposalDataSource = proposalDataSource,
            keystoneProposalRepository = keystoneProposalRepository,
            getSelectedWalletAccount = mockk {
                coEvery { this@mockk() } returns mockk<KeystoneAccount>(relaxed = true)
                every { observe() } returns flowOf(mockk<KeystoneAccount>(relaxed = true))
            },
        )

        invokeOnConfirmImmediate(vm, proposal)
        advanceUntilIdle()

        coVerify(exactly = 0) { proposalDataSource.submitTransaction(any<Proposal>(), any<UnifiedSpendingKey>()) }
        coVerifyOrder {
            keystoneProposalRepository.setMigrationSweepProposal(proposal, Zatoshi(500_000L))
            keystoneProposalRepository.createPCZTFromProposal()
        }
        assertEquals(
            listOf<Any>(co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionArgs),
            router.forwardedRoutes,
        )
    }

    // Covers item 5 of the plan-update/expired-transfer fixes: restartCurrentMigrationStep()'s own
    // doc requires its returned schedule to go through this normal confirmation flow rather than
    // being discarded in favor of an independently re-proposed one.
    @Test
    fun automaticModeReusesPendingRestartScheduleInsteadOfProposingAFreshOne() = runTest {
        val router = FakeNavigationRouter()
        val restartSchedule = MigrationSchedule(
            transfers = listOf(
                TransferProposal(
                    id = "restart_transfer_0",
                    amountZatoshi = 900_000L,
                    anchorHeight = 0L,
                    nextExecutableAfterHeight = 100L,
                    expiryHeight = 200L,
                )
            ),
            estimatedDurationHours = 1,
        )
        val restartRepo = mockk<RestartMigrationScheduleRepository>(relaxed = true) {
            every { consume() } returns restartSchedule
        }
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
        val vm = vm(
            router = router,
            proposalDataSource = mockk(relaxed = true),
            getOrchardMigrationSdk = mockk { coEvery { this@mockk() } returns sdk },
            mode = MigrationMode.AUTOMATIC,
            restartMigrationScheduleRepository = restartRepo,
        )
        val collectJob = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(1, vm.state.value.content?.transfers?.size)
        coVerify(exactly = 0) { sdk.proposeMigrationTransfers(any()) }
        coVerify(exactly = 1) { restartRepo.consume() }
        collectJob.cancel()
    }

    @Test
    fun automaticModeProposesFreshScheduleWhenNoRestartIsPending() = runTest {
        val router = FakeNavigationRouter()
        val freshSchedule = MigrationSchedule(
            transfers = listOf(
                TransferProposal(
                    id = "fresh_transfer_0",
                    amountZatoshi = 100_000L,
                    anchorHeight = 0L,
                    nextExecutableAfterHeight = 100L,
                    expiryHeight = 200L,
                ),
                TransferProposal(
                    id = "fresh_transfer_1",
                    amountZatoshi = 200_000L,
                    anchorHeight = 0L,
                    nextExecutableAfterHeight = 200L,
                    expiryHeight = 300L,
                ),
            ),
            estimatedDurationHours = 2,
        )
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true) {
            coEvery { proposeMigrationTransfers(any()) } returns freshSchedule
        }
        val vm = vm(
            router = router,
            proposalDataSource = mockk(relaxed = true),
            getOrchardMigrationSdk = mockk { coEvery { this@mockk() } returns sdk },
            mode = MigrationMode.AUTOMATIC,
        )
        val collectJob = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(2, vm.state.value.content?.transfers?.size)
        coVerify(exactly = 1) { sdk.proposeMigrationTransfers(any()) }
        collectJob.cancel()
    }

    // MigrationReviewVM's IMMEDIATE-mode `onConfirm` callback only becomes reachable through
    // `state.value.content`, which is backed by a `combine(...).stateIn(WhileSubscribed)` chain
    // that (by design, same as every other LCE-driven VM in this codebase, e.g.
    // MigrationCompleteVM) only starts computing once actively collected — exercising it through a
    // real subscriber is exactly the kind of Flow-timing plumbing this test isn't meant to be
    // about. `MigrationCompleteVMTest.invokeOnDone` establishes the same
    // call-the-private-handler-via-reflection pattern for the identical reason.
    private fun invokeOnConfirmImmediate(vm: MigrationReviewVM, proposal: Proposal, amountZatoshi: Long = 500_000L) {
        val method =
            MigrationReviewVM::class.java.getDeclaredMethod(
                "onConfirmImmediate",
                Proposal::class.java,
                Long::class.java,
            )
        method.isAccessible = true
        method.invoke(vm, proposal, amountZatoshi)
    }

    private fun vm(
        router: NavigationRouter,
        proposalDataSource: ProposalDataSource,
        getSelectedWalletAccount: GetSelectedWalletAccountUseCase = mockk {
            coEvery { this@mockk() } returns mockk<ZashiAccount>(relaxed = true)
            every { observe() } returns flowOf(mockk<ZashiAccount>(relaxed = true))
        },
        zashiSpendingKeyDataSource: ZashiSpendingKeyDataSource = mockk {
            coEvery { getZashiSpendingKey() } returns mockk<UnifiedSpendingKey>()
        },
        getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase = mockk<GetOrchardMigrationSdkUseCase>(relaxed = true),
        keystoneProposalRepository: co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository =
            mockk(relaxed = true),
        mode: MigrationMode = MigrationMode.IMMEDIATE,
        restartMigrationScheduleRepository: RestartMigrationScheduleRepository =
            mockk<RestartMigrationScheduleRepository>(relaxed = true) { every { consume() } returns null },
    ) = MigrationReviewVM(
        args = MigrationReviewArgs(mode = mode),
        getOrchardMigrationSdk = getOrchardMigrationSdk,
        pendingMigrationScheduleRepository = mockk<PendingMigrationScheduleRepository>(relaxed = true),
        restartMigrationScheduleRepository = restartMigrationScheduleRepository,
        finalizeMigrationSchedule = mockk<FinalizeMigrationScheduleUseCase>(relaxed = true),
        navigationRouter = router,
        exchangeRateRepository = mockk<ExchangeRateRepository>(relaxed = true) {
            every { state } returns MutableStateFlow(ExchangeRateState.OptedOut)
        },
        getSelectedWalletAccount = getSelectedWalletAccount,
        getOrchardBalance = mockk<GetOrchardBalanceUseCase> { coEvery { this@mockk() } returns Zatoshi(500_000L) },
        errorStateMapper = mockk<ErrorMapperUseCase>(relaxed = true),
        zashiSpendingKeyDataSource = zashiSpendingKeyDataSource,
        biometricRepository = mockk<BiometricRepository>(relaxed = true),
        proposalDataSource = proposalDataSource,
        keystoneProposalRepository = keystoneProposalRepository,
    )

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
