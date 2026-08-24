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
            // A claim this wallet actually broadcast. Deleting the account it is landing in would
            // leave confirmation searching a wallet that no longer exists.
            val fixture = Fixture(receipt(claimTxids = listOf("beef")))

            assertFailsWith<GiftClaimDestinationAccountInUseException> { fixture.useCase(fixture.keystone) }

            coVerify(exactly = 0) { fixture.biometrics.requestBiometrics(any()) }
            coVerify(exactly = 0) { fixture.accountDataSource.deleteAccount(any()) }
            coVerify(exactly = 0) { fixture.migrations.cancelMigrationWork(any()) }
        }

    @Test
    fun `does not disconnect over a submission this wallet may have started`() =
        runTest {
            // Past the durable marker the transaction may exist whatever came back.
            val fixture = Fixture(receipt(claimSubmissionAttemptedAt = "2026-08-23T00:01:00Z"))

            assertFailsWith<GiftClaimDestinationAccountInUseException> { fixture.useCase(fixture.keystone) }
        }

    @Test
    fun `disconnects over a card this wallet only read`() =
        runTest {
            // A receipt is written before the claim scan, so one exists for every card this wallet
            // looked at. Nothing settles those, and this path offers no way to proceed anyway — so
            // refusing over one would make the account undisconnectable for good.
            val fixture = Fixture(receipt())

            fixture.useCase(fixture.keystone)

            coVerify(exactly = 1) { fixture.accountDataSource.deleteAccount(any()) }
        }

    private class Fixture(
        storedReceipt: ReceivedGift,
    ) {
        val accountDataSource = mockk<AccountDataSource>(relaxed = true)
        val biometrics = mockk<BiometricRepository>(relaxed = true)
        val migrations = mockk<MigrationAppHooks>(relaxed = true)
        val keystone =
            mockk<KeystoneAccount>().also { account ->
                every { account.sdkAccount } returns
                    mockk<Account>().also { every { it.accountUuid } returns ACCOUNT_UUID }
            }
        val useCase: DisconnectUseCase

        init {
            val receipts =
                mockk<ReceivedGiftStorageProvider>().also { coEvery { it.getAll() } returns listOf(storedReceipt) }
            useCase = DisconnectUseCase(accountDataSource, biometrics, migrations, receipts)
        }
    }

    private companion object {
        val ACCOUNT_UUID: AccountUuid = AccountUuid.new(ByteArray(16))

        fun receipt(
            claimTxids: List<String> = emptyList(),
            claimSubmissionAttemptedAt: String? = null,
        ) = ReceivedGift(
            address = "card-address",
            network = "main",
            amountZatoshi = 100_000_000L,
            claimedAt = "2026-08-23T00:00:00Z",
            destinationAccountUuid = ACCOUNT_UUID.toStorageKeyId(),
            claimTxids = claimTxids,
            claimSubmissionAttemptedAt = claimSubmissionAttemptedAt,
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
    }
}
