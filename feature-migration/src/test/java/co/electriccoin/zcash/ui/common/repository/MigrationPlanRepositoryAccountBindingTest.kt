package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.WalletAddress
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.common.model.migration.MigrationPlan
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MigrationPlanRepositoryAccountBindingTest {
    private fun account(uuid: UUID): WalletAccount =
        mockk(relaxed = true) {
            every { sdkAccount } returns AccountFixture.new(accountUuid = uuid)
        }

    private fun plan(id: String) =
        MigrationPlan(
            id = id,
            createdAtEpochSeconds = 0L,
            transfers = emptyList(),
            mode = MigrationMode.AUTOMATIC,
        )

    @Test
    fun loadByAccountKeyIdIsIndependentOfSelectedAccount() =
        runTest {
            val uuidA = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val uuidB = UUID.fromString("00000000-0000-0000-0000-000000000002")
            val accountA = account(uuidA)
            val accountB = account(uuidB)
            val keyA = accountA.sdkAccount.accountUuid.toStorageKeyId()

            val selected = MutableStateFlow<WalletAccount?>(accountA)
            val accountDataSource = FakeAccountDataSourceForRepoTest(selected)
            val prefs = InMemoryPreferenceProviderForRepoTest()
            val holder = mockk<EncryptedPreferenceProvider> { coEvery { this@mockk() } returns prefs }
            val repo = MigrationPlanRepositoryImpl(holder, accountDataSource)

            // save under account A (selected)
            repo.save(plan("A"))

            // switch selected to B — explicit-account load of A still returns A's plan
            selected.value = accountB
            assertEquals("A", repo.load(keyA)?.id)

            // explicit-account load of a never-written key returns null
            assertNull(repo.load("deadbeefdeadbeef"))
        }
}

// ---------------------------------------------------------------------------
// Test doubles (local copies, mirroring MigrationTorPreferenceAccountScopingTest)
// ---------------------------------------------------------------------------

private class InMemoryPreferenceProviderForRepoTest : PreferenceProvider {
    private val map = mutableMapOf<String, String?>()

    override suspend fun hasKey(key: PreferenceKey) = map.containsKey(key.key)

    override suspend fun putString(key: PreferenceKey, value: String?) {
        map[key.key] = value
    }

    override suspend fun putStringSet(key: PreferenceKey, value: Set<String>?) = Unit

    override suspend fun putLong(key: PreferenceKey, value: Long?) {
        map[key.key] = value?.toString()
    }

    override suspend fun getLong(key: PreferenceKey): Long? = map[key.key]?.toLongOrNull()

    override suspend fun getString(key: PreferenceKey): String? = map[key.key]

    override suspend fun getStringSet(key: PreferenceKey): Set<String>? = null

    override fun observe(key: PreferenceKey): Flow<String?> = flow { emit(getString(key)) }

    override suspend fun remove(key: PreferenceKey) {
        map.remove(key.key)
    }

    override suspend fun clearPreferences(): Boolean {
        map.clear()
        return true
    }
}

private class FakeAccountDataSourceForRepoTest(
    private val selected: MutableStateFlow<WalletAccount?>
) : AccountDataSource {
    override val allAccounts: StateFlow<List<WalletAccount>?> = MutableStateFlow(null)
    override val selectedAccount: Flow<WalletAccount?> = selected
    override val zashiAccount: Flow<ZashiAccount?> = flowOf(null)

    override suspend fun getAllAccounts(): List<WalletAccount> = listOfNotNull(selected.value)

    override suspend fun getSelectedAccount(): WalletAccount = selected.value!!

    override suspend fun getZashiAccount(): ZashiAccount = error("unsupported")

    override suspend fun selectAccount(account: cash.z.ecc.android.sdk.model.Account) = error("unsupported")

    override suspend fun selectAccount(account: WalletAccount) = error("unsupported")

    override suspend fun importKeystoneAccount(
        ufvk: String,
        seedFingerprint: String,
        index: Long,
        birthday: BlockHeight?
    ): cash.z.ecc.android.sdk.model.Account = error("unsupported")

    override suspend fun requestNextShieldedAddress(): WalletAddress.Unified = error("unsupported")

    override suspend fun deleteAccount(account: WalletAccount) = error("unsupported")
}
