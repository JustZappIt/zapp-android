package co.electriccoin.zcash.ui.screen.migration.invalid

import androidx.navigation.NavBackStackEntry
import cash.z.ecc.android.sdk.AttentionReason
import cash.z.ecc.android.sdk.MigrationSchedule
import cash.z.ecc.android.sdk.MigrationState
import cash.z.ecc.android.sdk.MigrationTransferState
import cash.z.ecc.android.sdk.MigrationTransferStates
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.TransferProposal
import cash.z.ecc.android.sdk.fixture.AccountFixture
import co.electriccoin.zcash.ui.BaseNavigationCommand
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.migration.MigrationAttentionKind
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.common.model.migration.MigrationPlan
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransfer
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferStatus
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.common.repository.RestartMigrationScheduleRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.design.util.StringResource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class MigrationTransferInvalidVMTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun transfer(index: Int, id: Long, status: MigrationTransferStatus, expiryAtEpochSeconds: Long) =
        MigrationTransfer(
            index = index,
            amountZatoshi = 100_000L,
            scheduledAtEpochSeconds = 0L,
            status = status,
            expiryAtEpochSeconds = expiryAtEpochSeconds,
            id = id,
        )

    private fun plan(transfers: List<MigrationTransfer>) =
        MigrationPlan(
            id = "p1",
            createdAtEpochSeconds = 0L,
            transfers = transfers,
            mode = MigrationMode.AUTOMATIC,
        )

    @Test
    fun invalidTransferReasonShowsPlanUpdateKindAndTheOneNamedTransfer() =
        runTest {
            val plan =
                plan(
                    listOf(
                        transfer(0, 10L, MigrationTransferStatus.SENT, 100L),
                        transfer(1, 11L, MigrationTransferStatus.PENDING, 200L),
                    )
                )
            val sdk =
                mockk<OrchardMigrationSdk>(relaxed = true) {
                    coEvery { getMigrationState() } returns MigrationState.RequiresAttention(AttentionReason.InvalidTransfer(11L))
                    coEvery { getMigrationTransferStates() } returns null
                }
            val vm =
                vm(
                    plans = mockk(relaxed = true) { every { observe() } returns flowOf(plan) },
                    getOrchardMigrationSdk = mockk { coEvery { this@mockk() } returns sdk },
                )
            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            val content = vm.state.value.content
            assertEquals(MigrationAttentionKind.PLAN_UPDATE, content?.kind)
            assertEquals(StringResource.ByString("2"), content?.invalidRange)
            collectJob.cancel()
        }

    @Test
    fun transferExpiredReasonShowsTransferExpiredKindAndTheRealExpiredRangeNotEveryRemainingTransfer() =
        runTest {
            // t1 is PENDING but not yet expired at "now" (the VM compares against the real wall clock)
            // — the old cached-count logic would have wrongly included it (everything after the
            // completed count). Only t2 (already past its expiry) should show up.
            val now =
                kotlin.time.Clock.System
                    .now()
                    .epochSeconds
            val plan =
                plan(
                    listOf(
                        transfer(0, 10L, MigrationTransferStatus.SENT, now - 1_000L),
                        transfer(1, 11L, MigrationTransferStatus.PENDING, now + 10_000L),
                        transfer(2, 12L, MigrationTransferStatus.PENDING, now - 100L),
                    )
                )
            val sdk =
                mockk<OrchardMigrationSdk>(relaxed = true) {
                    coEvery { getMigrationState() } returns MigrationState.RequiresAttention(AttentionReason.TransferExpired)
                    coEvery { getMigrationTransferStates() } returns
                        MigrationTransferStates(
                            transfers =
                                listOf(
                                    MigrationTransferState(
                                        id = 10L,
                                        isTransfer = true,
                                        isSent = true,
                                        isProved = true,
                                        scheduledHeight = 1L,
                                        anchorBoundaryHeight = null,
                                    ),
                                    MigrationTransferState(
                                        id = 11L,
                                        isTransfer = true,
                                        isSent = false,
                                        isProved = false,
                                        scheduledHeight = 2L,
                                        anchorBoundaryHeight = null,
                                    ),
                                    MigrationTransferState(
                                        id = 12L,
                                        isTransfer = true,
                                        isSent = false,
                                        isProved = false,
                                        scheduledHeight = 3L,
                                        anchorBoundaryHeight = null,
                                    ),
                                ),
                            tipHeight = 3L,
                        )
                }
            val vm =
                vm(
                    plans = mockk(relaxed = true) { every { observe() } returns flowOf(plan) },
                    getOrchardMigrationSdk = mockk { coEvery { this@mockk() } returns sdk },
                )
            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            val content = vm.state.value.content
            assertEquals(MigrationAttentionKind.TRANSFER_EXPIRED, content?.kind)
            assertEquals(1, content?.remainingCount)
            assertEquals(StringResource.ByString("3"), content?.invalidRange)
            collectJob.cancel()
        }

    @Test
    fun onContinueStoresRestartedScheduleForReviewToReuseInsteadOfDiscardingIt() =
        runTest {
            val restartedSchedule =
                MigrationSchedule(
                    transfers =
                        listOf(
                            TransferProposal(
                                id = 100L,
                                amountZatoshi = 900_000L,
                                anchorHeight = 0L,
                                nextExecutableAfterHeight = 100L,
                                expiryHeight = 200L,
                            )
                        ),
                    estimatedDurationHours = 1,
                    proposalHandle = 0L,
                )
            val sdk =
                mockk<OrchardMigrationSdk>(relaxed = true) {
                    coEvery { getMigrationState() } returns MigrationState.RequiresAttention(AttentionReason.TransferExpired)
                    coEvery { getMigrationTransferStates() } returns null
                    coEvery { restartCurrentMigrationStep() } returns restartedSchedule
                }
            val restartRepo = mockk<RestartMigrationScheduleRepository>(relaxed = true)
            val router = FakeNavigationRouter()
            val vm =
                vm(
                    plans = mockk(relaxed = true) { every { observe() } returns flowOf(plan(emptyList())) },
                    getOrchardMigrationSdk = mockk { coEvery { this@mockk() } returns sdk },
                    restartMigrationScheduleRepository = restartRepo,
                    router = router,
                )
            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            invokeOnContinue(vm)
            advanceUntilIdle()

            coVerify(exactly = 1) { restartRepo.set(any(), restartedSchedule) }
            assertEquals(1, router.replacedRoutes.size)
            collectJob.cancel()
        }

    private fun invokeOnContinue(vm: MigrationTransferInvalidVM) {
        val method = MigrationTransferInvalidVM::class.java.getDeclaredMethod("onContinue")
        method.isAccessible = true
        method.invoke(vm)
    }

    private fun vm(
        plans: MigrationPlanRepository,
        getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
        restartMigrationScheduleRepository: RestartMigrationScheduleRepository = mockk(relaxed = true),
        router: NavigationRouter = FakeNavigationRouter(),
    ): MigrationTransferInvalidVM {
        val fakeAccount =
            AccountFixture.new(
                accountUuid = UUID.fromString("00000000-0000-0000-0000-000000000001")
            )
        val walletAccount: WalletAccount =
            mockk(relaxed = true) {
                every { sdkAccount } returns fakeAccount
            }
        val getSelectedWalletAccount =
            mockk<GetSelectedWalletAccountUseCase> {
                coEvery { this@mockk() } returns walletAccount
            }
        return MigrationTransferInvalidVM(
            getOrchardMigrationSdk = getOrchardMigrationSdk,
            getSelectedWalletAccount = getSelectedWalletAccount,
            migrationPlanRepository = plans,
            restartMigrationScheduleRepository = restartMigrationScheduleRepository,
            navigationRouter = router,
            errorStateMapper = mockk<ErrorMapperUseCase>(relaxed = true),
        )
    }

    private class FakeNavigationRouter : NavigationRouter {
        val replacedRoutes = mutableListOf<Any>()

        override fun forward(vararg routes: Any) = Unit

        override fun replace(vararg routes: Any) {
            replacedRoutes.addAll(routes)
        }

        override fun replaceAll(vararg routes: Any) = Unit

        override fun back() = Unit

        override fun backTo(route: KClass<*>) = Unit

        override fun custom(block: (NavBackStackEntry?) -> NavigationCommand?) = Unit

        override fun backToRoot() = Unit

        override fun observePipeline(): Flow<BaseNavigationCommand> = emptyFlow()
    }
}
