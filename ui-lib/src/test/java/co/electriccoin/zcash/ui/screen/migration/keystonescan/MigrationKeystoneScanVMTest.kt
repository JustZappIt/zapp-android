package co.electriccoin.zcash.ui.screen.migration.keystonescan

import androidx.navigation.NavBackStackEntry
import cash.z.ecc.android.sdk.KeystoneBatchDecodeResult
import cash.z.ecc.android.sdk.KeystoneBatchSignedPczts
import cash.z.ecc.android.sdk.MigrationSchedule
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.TransferProposal
import co.electriccoin.zcash.ui.BaseNavigationCommand
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.repository.PendingKeystoneMigrationPczts
import co.electriccoin.zcash.ui.common.repository.PendingKeystoneMigrationPcztsRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.PendingMigrationScheduleRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.FinalizeMigrationScheduleUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.Dispatchers
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class MigrationKeystoneScanVMTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun outdatedFirmwareOnFirstRoundBlocksWithoutFinalizing() =
        runTest {
            val sdk = fakeSdk(signedFirmwareBytes = "no stamp in these bytes".toByteArray(Charsets.US_ASCII))
            val pendingSchedule = PendingMigrationScheduleRepositoryImpl().apply { set(schedule()) }
            val pendingPczts = PendingKeystoneMigrationPcztsRepositoryImpl().apply { set(pending(roundIndex = 0)) }
            val finalize = mockk<FinalizeMigrationScheduleUseCase>(relaxed = true)
            val router = FakeNavigationRouter()
            val vm = vm(sdk, pendingSchedule, pendingPczts, finalize, router)

            vm.onScanned("frame")
            advanceUntilIdle()

            assertNotNull(vm.failureSheet.value)
            coVerify(exactly = 0) { finalize(any(), any()) }
            assertNotNull(pendingSchedule.get())
            assertNotNull(pendingPczts.get())
            assertEquals(0, pendingPczts.get()?.roundIndex)

            vm.failureSheet.value?.onDismiss?.invoke()
            assertNull(vm.failureSheet.value)
            assertEquals(1, router.backCount)
        }

    @Test
    fun upToDateFirmwareOnFirstRoundProceedsToFinalize() =
        runTest {
            val sdk = fakeSdk(signedFirmwareBytes = pcztBytesWithStamp(3, 0, 2))
            val pendingSchedule = PendingMigrationScheduleRepositoryImpl().apply { set(schedule()) }
            val pendingPczts = PendingKeystoneMigrationPcztsRepositoryImpl().apply { set(pending(roundIndex = 0)) }
            val finalize = mockk<FinalizeMigrationScheduleUseCase>(relaxed = true)
            val router = FakeNavigationRouter()
            val vm = vm(sdk, pendingSchedule, pendingPczts, finalize, router)

            vm.onScanned("frame")
            advanceUntilIdle()

            assertNull(vm.failureSheet.value)
            coVerify(exactly = 1) { finalize(any(), any()) }
            assertNull(pendingSchedule.get())
            assertNull(pendingPczts.get())
        }

    @Test
    fun outdatedFirmwareOnLaterRoundSkipsCheckAndProceeds() =
        runTest {
            val sdk = fakeSdk(signedFirmwareBytes = "no stamp in these bytes".toByteArray(Charsets.US_ASCII))
            val pendingSchedule = PendingMigrationScheduleRepositoryImpl().apply { set(schedule()) }
            val pendingPczts = PendingKeystoneMigrationPcztsRepositoryImpl().apply {
                set(
                    PendingKeystoneMigrationPczts(
                        requestId = byteArrayOf(1, 2, 3),
                        splitUnsignedPczt = null,
                        transferUnsignedPczts = (0 until 36).map { "t$it" to byteArrayOf(it.toByte()) },
                        roundIndex = 1,
                        accumulatedTransferSigned = (0 until 35).map { "t$it" to byteArrayOf(it.toByte()) },
                    )
                )
            }
            val finalize = mockk<FinalizeMigrationScheduleUseCase>(relaxed = true)
            val router = FakeNavigationRouter()
            val vm = vm(sdk, pendingSchedule, pendingPczts, finalize, router)

            vm.onScanned("frame")
            advanceUntilIdle()

            assertNull(vm.failureSheet.value)
            coVerify(exactly = 1) { finalize(any(), any()) }
        }

    private fun vm(
        sdk: OrchardMigrationSdk,
        pendingSchedule: PendingMigrationScheduleRepositoryImpl,
        pendingPczts: PendingKeystoneMigrationPcztsRepositoryImpl,
        finalize: FinalizeMigrationScheduleUseCase,
        router: FakeNavigationRouter,
    ) = MigrationKeystoneScanVM(
        args = MigrationKeystoneScanArgs(mode = MigrationMode.IMMEDIATE),
        getOrchardMigrationSdk = mockk<GetOrchardMigrationSdkUseCase> { coEvery { this@mockk() } returns sdk },
        pendingSchedule = pendingSchedule,
        pendingKeystonePczts = pendingPczts,
        finalizeMigrationSchedule = finalize,
        isTorEnabledStorageProvider = mockk(relaxed = true),
        navigationRouter = router,
    )

    private fun fakeSdk(signedFirmwareBytes: ByteArray): OrchardMigrationSdk =
        mockk(relaxed = true) {
            coEvery { decodeKeystoneSignBatchPart(any(), any()) } returns
                KeystoneBatchDecodeResult(complete = true, progress = 100, data = ByteArray(1))
            coEvery { applyKeystoneBatchSignatures(any(), any(), any()) } returns
                KeystoneBatchSignedPczts(splitSignedPczt = null, transferSignedPczts = listOf(signedFirmwareBytes))
        }

    private fun schedule() =
        MigrationSchedule(
            transfers = listOf(
                TransferProposal(
                    id = "t1",
                    amountZatoshi = 100_000L,
                    anchorHeight = 100L,
                    nextExecutableAfterHeight = 200L,
                    expiryHeight = 300L,
                )
            ),
            estimatedDurationHours = 1,
        )

    private fun pending(roundIndex: Int) =
        PendingKeystoneMigrationPczts(
            requestId = byteArrayOf(1, 2, 3),
            splitUnsignedPczt = null,
            transferUnsignedPczts = listOf("t1" to byteArrayOf(9, 9)),
            roundIndex = roundIndex,
        )

    private fun pcztBytesWithStamp(
        major: Int,
        minor: Int,
        build: Int
    ): ByteArray {
        val key = "keystone:fw_version".toByteArray(Charsets.US_ASCII)
        return byteArrayOf(0x01) + key + byteArrayOf(0x03, major.toByte(), minor.toByte(), build.toByte())
    }

    private class FakeNavigationRouter : NavigationRouter {
        var backCount = 0
            private set

        override fun forward(vararg routes: Any) = Unit

        override fun replace(vararg routes: Any) = Unit

        override fun replaceAll(vararg routes: Any) = Unit

        override fun back() {
            backCount++
        }

        override fun backTo(route: KClass<*>) = Unit

        override fun custom(block: (NavBackStackEntry?) -> NavigationCommand?) = Unit

        override fun backToRoot() = Unit

        override fun observePipeline(): Flow<BaseNavigationCommand> = emptyFlow()
    }
}
