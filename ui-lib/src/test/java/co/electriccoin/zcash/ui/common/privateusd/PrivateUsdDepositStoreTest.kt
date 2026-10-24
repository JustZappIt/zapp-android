// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.ui.common.provider.InMemoryPreferenceProvider
import co.electriccoin.zcash.ui.common.provider.StoreCorruptedException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import xyz.justzappit.offramp.deposits.DepositScan
import xyz.justzappit.offramp.deposits.DepositState
import xyz.justzappit.offramp.deposits.ScannedAddress
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrivateUsdDepositStoreTest {
    private val preferences = InMemoryPreferenceProvider()
    private val store = PrivateUsdDepositStore(preferences.encrypted())

    @Test
    fun `a new request goes first, and one kept again stays where it was`() =
        runTest {
            val first = depositRecord(index = 0, createdAt = 100)
            val second = depositRecord(index = 1, createdAt = 200)
            store.keep(listOf(first))
            store.keep(listOf(second))

            val sent = first.copy(state = DepositState.SENT, transactions = listOf(depositTx(1)))
            store.keep(listOf(sent))

            assertEquals(listOf(second, sent), store.records())
            assertEquals(listOf(second, sent), store.observe.first())
        }

    @Test
    fun `a request and the one it replaces are kept in one write`() =
        runTest {
            val stale = depositRecord(createdAt = 100)
            store.keep(listOf(stale))
            val fresh = depositRecord(createdAt = 200)

            store.keep(listOf(stale.copy(state = DepositState.REPLACED, endedAt = 200), fresh))

            assertEquals(listOf(DepositState.SIGNED, DepositState.REPLACED), store.records().map { it.state })
            assertEquals(fresh, store.records().first())
        }

    @Test
    fun `everything under way is kept, and the newest hundred of the rest`() =
        runTest {
            val underWay = depositRecord(index = 7, createdAt = 1)
            store.keep(listOf(underWay))
            val finished =
                (1..120).map { depositRecord(index = 0, createdAt = 1_000L + it, state = DepositState.REFUSED) }
            finished.forEach { store.keep(listOf(it)) }

            val kept = store.records()

            assertEquals(101, kept.size)
            assertTrue(underWay in kept)
            assertEquals(finished.takeLast(100).reversed(), kept.filter { it.state.isFinished })
        }

    @Test
    fun `the last scan reads back`() =
        runTest {
            assertNull(store.scan())
            val scan = DepositScan(100, showing = 3, used = listOf(ScannedAddress(0, Usdc6.ofMicros(1), true)), 2)

            store.keepScan(scan)
            store.keep(listOf(depositRecord()))

            assertEquals(scan, store.scan())
        }

    @Test
    fun `deposits a newer build wrote fail loudly, and are never written over`() =
        runTest {
            val newer = """{"records":[],"laterField":true}"""
            preferences.putString(PreferenceKey(KEY), newer)

            assertFailsWith<StoreCorruptedException> { store.records() }
            assertFailsWith<StoreCorruptedException> { store.keep(listOf(depositRecord())) }
            assertEquals(newer, preferences.getString(PreferenceKey(KEY)))
            assertEquals(emptyList(), store.observe.first())
        }

    private companion object {
        const val KEY = "private_usd_deposits_v1"
    }
}
