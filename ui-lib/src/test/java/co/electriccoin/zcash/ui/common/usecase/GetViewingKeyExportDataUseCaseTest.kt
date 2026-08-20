// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.Account
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class GetViewingKeyExportDataUseCaseTest {
    @Test
    fun returnsExactViewingKeys() =
        runTest {
            val account = walletAccount(name = "Zapp", index = 0, ufvk = UFVK, uivk = UIVK)
            val useCase = useCase(listOf(account))

            val full = useCase(account.sdkAccount.accountUuid, ViewingKeyType.UFVK)
            val incoming = useCase(account.sdkAccount.accountUuid, ViewingKeyType.UIVK)

            assertEquals(UFVK, full?.encodedKey)
            assertEquals(UIVK, incoming?.encodedKey)
        }

    @Test
    fun supportsMultipleAccountSelection() =
        runTest {
            val zapp = walletAccount(name = "Zapp", index = 0, ufvk = "uview-zapp", uivk = "uivk-zapp")
            val keystone = walletAccount(name = "Keystone", index = 1, ufvk = "uview-keystone", uivk = null)
            val useCase = useCase(listOf(zapp, keystone))

            val accounts = useCase.getAccounts()
            val selected = useCase(accounts[1].accountId, ViewingKeyType.UFVK)

            assertEquals(2, accounts.size)
            assertEquals("uview-keystone", selected?.encodedKey)
        }

    @Test
    fun returnsNullWhenKeyOrAccountIsUnavailable() =
        runTest {
            val account = walletAccount(name = "Keystone", index = 2, ufvk = UFVK, uivk = null)
            val useCase = useCase(listOf(account))

            assertNull(useCase(account.sdkAccount.accountUuid, ViewingKeyType.UIVK))
            assertNull(useCase(AccountFixture.new().accountUuid, ViewingKeyType.UFVK))
        }

    @Test
    fun secretModelToStringIsRedacted() =
        runTest {
            val account = walletAccount(name = "Zapp", index = 0, ufvk = UFVK, uivk = UIVK)
            val result = requireNotNull(useCase(listOf(account))(account.sdkAccount.accountUuid, ViewingKeyType.UFVK))

            assertFalse(result.toString().contains(UFVK))
            assertEquals("ViewingKeyExportData(REDACTED)", result.toString())
        }

    private fun useCase(accounts: List<WalletAccount>): GetViewingKeyExportDataUseCase {
        val accountDataSource =
            mockk<AccountDataSource> {
                coEvery { getAllAccounts() } returns accounts
            }
        return GetViewingKeyExportDataUseCase(accountDataSource)
    }

    private fun walletAccount(
        name: String,
        index: Long,
        ufvk: String?,
        uivk: String?,
    ): WalletAccount {
        val baseAccount =
            AccountFixture.new(
                accountName = name,
                accountUuid = UUID.nameUUIDFromBytes(name.toByteArray()),
                hdAccountIndex =
                    cash.z.ecc.android.sdk.model.Zip32AccountIndex
                        .new(index),
                ufvk = ufvk ?: "fixture-placeholder",
                uivk = uivk,
            )
        val sdkAccount =
            mockk<Account> {
                every { accountUuid } returns baseAccount.accountUuid
                every { this@mockk.ufvk } returns ufvk
                every { this@mockk.uivk } returns uivk
                every { hdAccountIndex } returns baseAccount.hdAccountIndex
            }
        return ZashiAccount(
            sdkAccount = sdkAccount,
            unified = mockk(relaxed = true),
            sapling = mockk(relaxed = true),
            ironwoodBalance = mockk(relaxed = true),
            transparent = mockk(relaxed = true),
            isSelected = false,
        )
    }

    private companion object {
        const val UFVK = "uview1-native-full-key-preserved"
        const val UIVK = "uivk1-native-incoming-key-preserved"
    }
}
