// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapStoreImpl
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapStoreImpl
import co.electriccoin.zcash.ui.common.provider.InMemoryPreferenceProvider
import co.electriccoin.zcash.ui.common.provider.StoreCorruptedException
import co.electriccoin.zcash.ui.screen.privateusd.toZec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.justzappit.offramp.atomicswap.ReversePhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateUsdSpendGuardTest {
    @Test
    fun `a conversion starting is identified as a conversion while further sends remain blocked`() =
        runTest {
            val preferences = InMemoryPreferenceProvider().encrypted()
            val guard =
                PrivateUsdSpendGuard(
                    PrivateUsdSendLog(preferences),
                    AtomicSwapStoreImpl(preferences),
                    ReverseSwapStoreImpl(preferences),
                    backgroundScope,
                )
            val complete = CompletableDeferred<Unit>()
            val conversion = launch { guard.startConversion { complete.await() } }
            runCurrent()

            assertEquals(PrivateUsdSpendStatus.CONVERTING, guard.state.value)
            assertFalse(guard.state.value.canSend)
            assertEquals(PrivateUsdSendOutcome.Busy, guard.send { error("cannot send") })

            complete.complete(Unit)
            conversion.join()
            runCurrent()
            assertEquals(PrivateUsdSpendStatus.AVAILABLE, guard.state.value)
        }

    @Test
    fun `an active conversion blocks sends and a finished one releases the wallet`() =
        runTest {
            val preferences = InMemoryPreferenceProvider().encrypted()
            val reverse = ReverseSwapStoreImpl(preferences)
            val guard =
                PrivateUsdSpendGuard(
                    PrivateUsdSendLog(preferences),
                    AtomicSwapStoreImpl(preferences),
                    reverse,
                    backgroundScope,
                )
            reverse.save(toZec(3, ReversePhase.AWAITING_READY))
            runCurrent()

            assertEquals(PrivateUsdSpendStatus.CONVERTING, guard.state.value)
            assertEquals(PrivateUsdSendOutcome.Busy, guard.send { error("no send should start") })

            reverse.save(toZec(3, ReversePhase.COMPLETE))
            runCurrent()
            assertEquals(PrivateUsdSpendStatus.AVAILABLE, guard.state.value)
        }

    @Test
    fun `conversion acceptance cannot overlap a send`() =
        runTest {
            val preferences = InMemoryPreferenceProvider().encrypted()
            val guard =
                PrivateUsdSpendGuard(
                    PrivateUsdSendLog(preferences),
                    AtomicSwapStoreImpl(preferences),
                    ReverseSwapStoreImpl(preferences),
                    backgroundScope
                )
            val complete = CompletableDeferred<Unit>()
            val first =
                launch {
                    guard.send {
                        complete.await()
                        PrivateUsdSendOutcome.NotSent
                    }
                }
            runCurrent()

            assertFailsWith<PrivateUsdSpendBlockedException> { guard.startConversion { error("cannot accept") } }
            complete.complete(Unit)
            first.join()
        }

    @Test
    fun `unreadable conversion records prevent accepting another conversion`() =
        runTest {
            val preferences = InMemoryPreferenceProvider()
            val key = PreferenceKey("reverse_swap_v1")
            preferences.putString(key, "corrupt")
            val encrypted = preferences.encrypted()
            val guard =
                PrivateUsdSpendGuard(
                    PrivateUsdSendLog(encrypted),
                    AtomicSwapStoreImpl(encrypted),
                    ReverseSwapStoreImpl(encrypted),
                    backgroundScope,
                )

            assertFailsWith<StoreCorruptedException> { guard.startConversion { error("cannot accept") } }
            assertEquals("corrupt", preferences.getString(key))
        }

    @Test
    fun `an unreadable recovery log prevents new spending without overwriting it`() =
        runTest {
            val preferences = InMemoryPreferenceProvider()
            val key = PreferenceKey("private_usd_sends_v1")
            preferences.putString(key, "corrupt")
            val encrypted = preferences.encrypted()
            val guard =
                PrivateUsdSpendGuard(
                    PrivateUsdSendLog(encrypted),
                    AtomicSwapStoreImpl(encrypted),
                    ReverseSwapStoreImpl(encrypted),
                    backgroundScope
                )
            runCurrent()

            assertEquals(PrivateUsdSpendStatus.UNREADABLE, guard.state.value)
            assertFalse(guard.state.value.canSend)
            assertFailsWith<StoreCorruptedException> { guard.send { error("cannot send") } }
            assertEquals("corrupt", preferences.getString(key))
        }
}
