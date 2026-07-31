package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.fixture.AccountFixture
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.migration.sim.FakeOrchardMigrationSdk
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.ui.common.provider.PendingMigrationTorFailureStorageProvider
import co.electriccoin.zcash.ui.common.repository.PendingKeystoneMigrationPcztsRepository
import co.electriccoin.zcash.ui.common.repository.RestartMigrationScheduleRepository
import co.electriccoin.zcash.work.MigrationScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

class RestartMigrationUseCaseTest {
    /** Matches [co.electriccoin.zcash.ui.common.provider.MigrationTorPreferenceAccountScopingTest]'s
     *  own `account()` helper: a minimal [WalletAccount] stub with a stable, distinct accountUuid. */
    private fun account(uuid: UUID): WalletAccount =
        mockk(relaxed = true) {
            every { sdkAccount } returns AccountFixture.new(accountUuid = uuid)
        }

    @Test
    fun `invoke clears the run and clears all scheduled work for the selected account`() =
        runTest {
            val selected = account(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
            val accountKeyId = selected.sdkAccount.accountUuid.toStorageKeyId()

            // Seed the fake with an in-progress run so clearMigration has something to clear.
            val fakeSdk =
                FakeOrchardMigrationSdk().apply {
                    addTx(
                        FakeOrchardMigrationSdk.SimTx(
                            id = 1L,
                            isTransfer = true,
                            layer = 0,
                            scheduledHeight = 10L,
                            anchorBoundary = null,
                        )
                    )
                }

            val accountDataSource =
                mockk<AccountDataSource> {
                    coEvery { getSelectedAccount() } returns selected
                }
            val scheduler = mockk<MigrationScheduler>(relaxed = true)
            val torFailure = mockk<PendingMigrationTorFailureStorageProvider>(relaxed = true)
            val restartSchedule = mockk<RestartMigrationScheduleRepository>(relaxed = true)
            val keystonePczt = mockk<PendingKeystoneMigrationPcztsRepository>(relaxed = true)
            val notifier = mockk<MigrationNotifier>(relaxed = true)

            val useCase =
                RestartMigrationUseCase(
                    accountDataSource = accountDataSource,
                    getOrchardMigrationSdk =
                        mockk<GetOrchardMigrationSdkUseCase> {
                            coEvery { this@mockk() } returns fakeSdk
                        },
                    migrationScheduler = scheduler,
                    pendingMigrationTorFailureStorageProvider = torFailure,
                    restartMigrationScheduleRepository = restartSchedule,
                    pendingKeystoneMigrationPcztsRepository = keystonePczt,
                    migrationNotifier = notifier,
                )

            useCase()

            assertTrue(fakeSdk.clearMigrationCalled)
            verify { scheduler.cancel(accountKeyId) }
            coVerify { torFailure.store(accountKeyId, false) }
            verify { restartSchedule.consume(accountKeyId) }
            verify { keystonePczt.clear() }
            verify { notifier.cancel(accountKeyId) }
        }
}
