// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.migration.MigrationAppHooks
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.provider.ReceivedGiftStorageProvider
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.screen.gift.model.GiftLinkPayload
import co.electriccoin.zcash.ui.screen.gift.model.ReceivedGift
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DisconnectUseCaseTest {
    @Test
    fun `does not disconnect an account receiving an unsettled gift claim`() =
        runTest {
            val accountDataSource = mockk<AccountDataSource>(relaxed = true)
            val biometrics = mockk<BiometricRepository>(relaxed = true)
            val migrations = mockk<MigrationAppHooks>(relaxed = true)
            val receipts = mockk<ReceivedGiftStorageProvider>()
            val accountUuid = AccountUuid.new(ByteArray(16))
            val sdkAccount = mockk<Account>().also { every { it.accountUuid } returns accountUuid }
            val keystone = mockk<KeystoneAccount>().also { every { it.sdkAccount } returns sdkAccount }
            coEvery { receipts.getAll() } returns
                listOf(
                    ReceivedGift(
                        address = "card-address",
                        network = "main",
                        amountZatoshi = 100_000_000L,
                        claimedAt = "2026-08-23T00:00:00Z",
                        destinationAccountUuid = accountUuid.toStorageKeyId(),
                        claimLink =
                            GiftLinkPayload(
                                v = 1,
                                network = "main",
                                amountZatoshi = "100000000",
                                mnemonic = "test mnemonic",
                                birthdayHeight = 1L,
                                createdAt = "2026-08-23T00:00:00Z",
                            ),
                    )
                )
            val useCase = DisconnectUseCase(accountDataSource, biometrics, migrations, receipts)

            assertFailsWith<GiftClaimDestinationAccountInUseException> { useCase(keystone) }

            coVerify(exactly = 0) { biometrics.requestBiometrics(any()) }
            coVerify(exactly = 0) { accountDataSource.deleteAccount(any()) }
            coVerify(exactly = 0) { migrations.cancelMigrationWork(any()) }
        }
}
