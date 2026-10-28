// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.repository

import co.electriccoin.zcash.ui.common.provider.RailgunKeyProvider
import co.electriccoin.zcash.ui.common.provider.RailgunMnemonicProvider
import co.electriccoin.zcash.ui.common.security.AUTHENTICATE_TIMEOUT
import co.electriccoin.zcash.ui.common.security.AppLock
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.justzappit.railgun.RailgunAddress
import xyz.justzappit.railgun.RailgunBalances
import xyz.justzappit.railgun.RailgunFees
import xyz.justzappit.railgun.RailgunNetwork
import xyz.justzappit.railgun.RailgunRefresh
import xyz.justzappit.railgun.RailgunSession
import xyz.justzappit.railgun.RailgunWallet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class RailgunEngineTest {
    private val session = mockk<RailgunSession>(relaxed = true)
    private val wallet = mockk<RailgunWallet>(relaxed = true)
    private val state = MutableStateFlow(RailgunWalletState(RailgunWalletState.Phase.IDLE, RailgunNetwork.SEPOLIA))
    private val answer = CompletableDeferred<RailgunRefresh>()
    private var openSession: RailgunSession? = null

    init {
        every { session.address } returns ADDRESS
        every { session.fees } returns FEES
        coEvery { session.refresh() } coAnswers { answer.await() }
        every { wallet.session } answers { openSession }
        coEvery { wallet.open(any(), any(), any()) } answers {
            openSession = session
            session
        }
        coEvery { wallet.close() } answers { openSession = null }
        coEvery { wallet.wipe() } answers { openSession = null }
    }

    @Test
    fun `a lock that lasts the authentication timeout stops the use in flight and closes the page`() =
        runTest {
            val engine = engine()
            val sync = syncInFlight(engine)

            engine.onAppLock(AppLock.LOCKED)
            advanceTimeBy(AUTHENTICATE_TIMEOUT)
            runCurrent()

            assertTrue(sync.isCancelled)
            coVerify(exactly = 1) { wallet.close() }
            assertEquals(RailgunWalletState.Phase.IDLE, state.value.phase)
        }

    @Test
    fun `an unlock within the timeout leaves the page, and the use in flight, to run on`() =
        runTest {
            val engine = engine()
            val sync = syncInFlight(engine)

            engine.onAppLock(AppLock.LOCKED)
            advanceTimeBy(AUTHENTICATE_TIMEOUT - 1.minutes)
            engine.onAppLock(AppLock.UNLOCKED)
            advanceTimeBy(AUTHENTICATE_TIMEOUT)
            runCurrent()

            coVerify(exactly = 0) { wallet.close() }
            answer.complete(REFRESHED)
            assertEquals(REFRESHED, sync.await())
        }

    @Test
    fun `after the close a use waits for the unlock, then opens the page again`() =
        runTest {
            val engine = engine()
            syncInFlight(engine)
            engine.onAppLock(AppLock.LOCKED)
            advanceTimeBy(AUTHENTICATE_TIMEOUT)
            runCurrent()
            coVerify(exactly = 1) { wallet.close() }

            val next = async { engine.withSession { it.fees } }
            advanceTimeBy(AUTHENTICATE_TIMEOUT)
            runCurrent()
            assertFalse(next.isCompleted)
            coVerify(exactly = 1) { wallet.open(any(), any(), any()) }

            engine.onAppLock(AppLock.UNLOCKED)
            assertEquals(FEES, next.await())
            coVerify(exactly = 2) { wallet.open(any(), any(), any()) }
        }

    @Test
    fun `a reset wins over the lock, stops its timer, and fails a use at once rather than waiting`() =
        runTest {
            val engine = engine()
            val sync = syncInFlight(engine)
            engine.onAppLock(AppLock.LOCKED)

            engine.reset()
            advanceTimeBy(AUTHENTICATE_TIMEOUT)
            runCurrent()

            assertTrue(sync.isCancelled)
            coVerify(exactly = 1) { wallet.wipe() }
            coVerify(exactly = 0) { wallet.close() }
            assertFailsWith<IllegalStateException> { engine.withSession { it.fees } }
        }

    @Test
    fun `the gate up closes the page at once, stopping the use in flight`() =
        runTest {
            val engine = engine()
            val sync = syncInFlight(engine)

            engine.onAppLock(AppLock.GATE_UP)
            runCurrent()

            assertTrue(sync.isCancelled)
            coVerify(exactly = 1) { wallet.close() }
            assertEquals(RailgunWalletState.Phase.IDLE, state.value.phase)
        }

    @Test
    fun `the gate up with no page open calls nothing`() =
        runTest {
            val engine = engine()

            engine.onAppLock(AppLock.GATE_UP)
            runCurrent()

            coVerify(exactly = 0) { wallet.close() }
            coVerify(exactly = 0) { wallet.open(any(), any(), any()) }
            assertEquals(RailgunWalletState.Phase.IDLE, state.value.phase)
        }

    @Test
    fun `the gate up during the grace closes the page then, not once the grace ends`() =
        runTest {
            val engine = engine()
            val sync = syncInFlight(engine)
            engine.onAppLock(AppLock.LOCKED)
            advanceTimeBy(AUTHENTICATE_TIMEOUT - 1.minutes)

            engine.onAppLock(AppLock.GATE_UP)
            runCurrent()
            assertTrue(sync.isCancelled)
            coVerify(exactly = 1) { wallet.close() }

            advanceTimeBy(AUTHENTICATE_TIMEOUT)
            runCurrent()
            coVerify(exactly = 1) { wallet.close() }
        }

    // A sync the page is still answering, so no idle close is pending either.
    private fun TestScope.syncInFlight(engine: RailgunEngine): Deferred<RailgunRefresh> {
        val sync = async { engine.withSession { it.refresh() } }
        runCurrent()
        assertFalse(sync.isCompleted)
        return sync
    }

    private fun TestScope.engine(): RailgunEngine {
        val keys = mockk<RailgunKeyProvider>()
        coEvery { keys.encryptionKey() } answers { ByteArray(KEY_BYTES) }
        val mnemonic = mockk<RailgunMnemonicProvider>()
        coEvery { mnemonic.address() } returns ADDRESS
        coEvery { mnemonic.withMnemonic(any<suspend (CharArray) -> RailgunSession>()) } coAnswers {
            firstArg<suspend (CharArray) -> RailgunSession>()(CharArray(0))
        }
        return RailgunEngine(wallet, keys, mnemonic, RailgunNetwork.SEPOLIA, state, backgroundScope)
    }

    private companion object {
        const val KEY_BYTES = 32
        val ADDRESS =
            RailgunAddress(
                "0zk1qyrs4qyrd08p6uep0fc2y8njktgcpezts3rpaq6q0ln948ecjkw8prv7j6f" +
                    "e3z53llz8ursderja0juwv5pgnv8x5klmmwkv8q38h9n704h4d4qjyw7n5qk68nx"
            )
        val FEES = RailgunFees(25)
        val REFRESHED = RailgunRefresh(RailgunBalances(emptyMap()), emptyList())
    }
}
