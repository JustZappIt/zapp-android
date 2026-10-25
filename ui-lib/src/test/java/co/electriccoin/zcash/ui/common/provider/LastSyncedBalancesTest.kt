// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.AccountBalance
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class LastSyncedBalancesTest {
    private val balances = MutableStateFlow(split(ironwood = FUNDS))
    private val status = MutableStateFlow(Synchronizer.Status.SYNCED)

    @Test
    fun `an import's sync shows the split from before it`() =
        runTest {
            val seen = collect()
            // The SDK collapses the balance into Orchard a moment before it reports the sync.
            balances.value = split(orchard = FUNDS)
            status.value = Synchronizer.Status.SYNCING
            advanceTimeBy(SECOND)
            balances.value = split(ironwood = FUNDS)
            status.value = Synchronizer.Status.SYNCED
            advanceTimeBy(SECOND)

            assertFalse(split(orchard = FUNDS) in seen)
            assertEquals(split(ironwood = FUNDS), seen.last())
        }

    @Test
    fun `a change while synced shows once it settles`() =
        runTest {
            val seen = collect()
            balances.value = split(ironwood = FUNDS - SPENT)
            advanceTimeBy(SECOND)

            assertEquals(split(ironwood = FUNDS - SPENT), seen.last())
        }

    @Test
    fun `before the first sync, balances show as they come`() =
        runTest {
            status.value = Synchronizer.Status.SYNCING
            balances.value = split(orchard = FUNDS)
            val seen = collect()

            assertEquals(listOf(split(orchard = FUNDS)), seen)
        }

    private fun TestScope.collect(): List<Map<AccountUuid, AccountBalance>?> {
        val seen = mutableListOf<Map<AccountUuid, AccountBalance>?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            lastSyncedBalances(balances, status).toList(seen)
        }
        advanceTimeBy(SECOND)
        return seen
    }

    private fun split(
        orchard: Long = 0,
        ironwood: Long = 0,
    ) = mapOf(
        ACCOUNT to
            AccountBalance(
                sapling = pool(0),
                orchard = pool(orchard),
                ironwood = pool(ironwood),
                unshielded = Zatoshi(0),
            )
    )

    private fun pool(available: Long) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(0))

    private companion object {
        val ACCOUNT = AccountUuid.new(ByteArray(16) { 1 })
        const val FUNDS = 9_984_965_333L
        const val SPENT = 100_000_000L
        const val SECOND = 1_000L
    }
}
