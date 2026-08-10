package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.provider.GetVersionInfoProvider
import co.electriccoin.zcash.ui.fixture.VersionInfoFixture
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class GetViewingKeyExportDataUseCaseTest {
    @Test
    fun returnsExactUfvkAndUivkForSelectedAccount() =
        runTest {
            val account = walletAccount(name = "Zapp", index = 0, ufvk = UFVK, uivk = UIVK)
            val useCase = useCase(accounts = listOf(account))

            val full =
                assertIs<ViewingKeyExportResult.Available>(
                    useCase(account.sdkAccount.accountUuid, ViewingKeyType.UFVK),
                )
            val incoming =
                assertIs<ViewingKeyExportResult.Available>(useCase(account.sdkAccount.accountUuid, ViewingKeyType.UIVK))

            assertEquals(UFVK, full.encodedKey)
            assertEquals(UIVK, incoming.encodedKey)
            assertEquals(setOf(ViewingKeyType.UFVK, ViewingKeyType.UIVK), full.availableKeyTypes)
        }

    @Test
    fun supportsMultipleAccountSelection() =
        runTest {
            val zapp = walletAccount(name = "Zapp", index = 0, ufvk = "uview-zapp", uivk = "uivk-zapp")
            val keystone = walletAccount(name = "Keystone", index = 1, ufvk = "uview-keystone", uivk = null)
            val useCase = useCase(accounts = listOf(zapp, keystone))

            val accounts = useCase.getAccounts()
            val selected =
                assertIs<ViewingKeyExportResult.Available>(
                    useCase(accounts[1].accountId, ViewingKeyType.UFVK)
                )

            assertEquals(2, accounts.size)
            assertEquals(1L, selected.accountIndex)
            assertEquals("uview-keystone", selected.encodedKey)
        }

    @Test
    fun returnsExplicitUnavailableResultForMissingKey() =
        runTest {
            val account = walletAccount(name = "Keystone", index = 2, ufvk = UFVK, uivk = null)
            val incomingResult = useCase(listOf(account))(account.sdkAccount.accountUuid, ViewingKeyType.UIVK)
            val incomingOnly = walletAccount(name = "Incoming", index = 3, ufvk = null, uivk = UIVK)
            val fullResult =
                useCase(listOf(incomingOnly))(incomingOnly.sdkAccount.accountUuid, ViewingKeyType.UFVK)

            val unavailableIncoming = assertIs<ViewingKeyExportResult.Unavailable>(incomingResult)
            val unavailableFull = assertIs<ViewingKeyExportResult.Unavailable>(fullResult)
            assertEquals(ViewingKeyType.UIVK, unavailableIncoming.requestedKeyType)
            assertEquals(setOf(ViewingKeyType.UFVK), unavailableIncoming.availableKeyTypes)
            assertEquals(ViewingKeyType.UFVK, unavailableFull.requestedKeyType)
            assertEquals(setOf(ViewingKeyType.UIVK), unavailableFull.availableKeyTypes)
        }

    @Test
    fun returnsMainnetAndTestnetMetadata() =
        runTest {
            val account = walletAccount(name = "Zapp", index = 0, ufvk = UFVK, uivk = UIVK)
            val mainnet =
                assertIs<ViewingKeyExportResult.Available>(
                    useCase(listOf(account), ZcashNetwork.Mainnet)(account.sdkAccount.accountUuid, ViewingKeyType.UFVK)
                )
            val testnet =
                assertIs<ViewingKeyExportResult.Available>(
                    useCase(listOf(account), ZcashNetwork.Testnet)(account.sdkAccount.accountUuid, ViewingKeyType.UFVK)
                )

            assertEquals(ZcashNetwork.Mainnet, mainnet.network)
            assertEquals(ZcashNetwork.Testnet, testnet.network)
        }

    @Test
    fun secretModelToStringIsRedacted() =
        runTest {
            val account = walletAccount(name = "Zapp", index = 0, ufvk = UFVK, uivk = UIVK)
            val result =
                assertIs<ViewingKeyExportResult.Available>(
                    useCase(listOf(account))(account.sdkAccount.accountUuid, ViewingKeyType.UFVK)
                )

            assertFalse(result.toString().contains(UFVK))
            assertEquals("ViewingKeyExportResult.Available(REDACTED)", result.toString())
        }

    private fun useCase(
        accounts: List<WalletAccount>,
        network: ZcashNetwork = ZcashNetwork.Mainnet,
    ): GetViewingKeyExportDataUseCase {
        val accountDataSource =
            mockk<AccountDataSource> {
                coEvery { getAllAccounts() } returns accounts
            }
        val versionInfoProvider = mockk<GetVersionInfoProvider>()
        every { versionInfoProvider.invoke() } returns VersionInfoFixture.new().copy(network = network)
        return GetViewingKeyExportDataUseCase(accountDataSource, versionInfoProvider)
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
