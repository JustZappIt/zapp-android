// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.Synchronizer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Pins the "Connecting" watchdog: it fires once a startup state has lasted the whole window, not
 * a moment before, and never for an engine that has already reached the server.
 *
 * `runTest` drives these in virtual time, so the 90-second window costs nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncStartupTest {
    @Test
    fun `fires once a startup state outlasts the window`() =
        runTest {
            val stalls = mutableListOf<SyncStartup>()
            val job =
                launch {
                    watchSyncStartup(flowOf(SyncStartup.AwaitingEngine)) { stalls += it }
                }
            advanceTimeBy(SYNC_STARTUP_STALL_TIMEOUT - 1.seconds)
            runCurrent()
            assertEquals(emptyList(), stalls)

            advanceTimeBy(2.seconds)
            runCurrent()
            assertEquals(listOf<SyncStartup>(SyncStartup.AwaitingEngine), stalls)
            job.cancel()
        }

    @Test
    fun `a state change restarts the window`() =
        runTest {
            val states = MutableStateFlow<SyncStartup>(SyncStartup.AwaitingEngine)
            val stalls = mutableListOf<SyncStartup>()
            val job = launch { watchSyncStartup(states) { stalls += it } }

            advanceTimeBy(SYNC_STARTUP_STALL_TIMEOUT - 10.seconds)
            states.value = SyncStartup.Starting(Synchronizer.Status.INITIALIZING)
            advanceTimeBy(SYNC_STARTUP_STALL_TIMEOUT - 10.seconds)
            runCurrent()
            assertEquals(emptyList(), stalls)

            advanceTimeBy(20.seconds)
            runCurrent()
            assertEquals(listOf<SyncStartup>(SyncStartup.Starting(Synchronizer.Status.INITIALIZING)), stalls)
            job.cancel()
        }

    @Test
    fun `never fires once the engine reaches the server`() =
        runTest {
            val states = MutableStateFlow<SyncStartup>(SyncStartup.Starting(Synchronizer.Status.INITIALIZING))
            val stalls = mutableListOf<SyncStartup>()
            val job = launch { watchSyncStartup(states) { stalls += it } }

            advanceTimeBy(30.seconds)
            states.value = SyncStartup.Idle
            advanceTimeBy(SYNC_STARTUP_STALL_TIMEOUT * 4)
            runCurrent()
            assertEquals(emptyList(), stalls)
            job.cancel()
        }

    @Test
    fun `no wallet means nothing to watch`() =
        runTest {
            val states =
                syncStartupStates(
                    synchronizer = flowOf(null),
                    hasWallet = flowOf(false),
                ).toList()
            assertEquals(listOf<SyncStartup>(SyncStartup.Idle), states)
        }

    @Test
    fun `a wallet without an engine is awaiting one`() =
        runTest {
            val states =
                syncStartupStates(
                    synchronizer = flowOf(null),
                    hasWallet = flowOf(true),
                ).toList()
            assertEquals(listOf<SyncStartup>(SyncStartup.AwaitingEngine), states)
        }

    @Test
    fun `an engine is watched only until it first leaves startup`() =
        runTest {
            val engine = mockk<Synchronizer>()
            every { engine.status } returns
                flowOf(
                    Synchronizer.Status.INITIALIZING,
                    Synchronizer.Status.SYNCING,
                    // A later stop (migration pause, shutdown) is not a startup stall.
                    Synchronizer.Status.STOPPED,
                )

            val states =
                syncStartupStates(
                    synchronizer = flowOf(engine),
                    hasWallet = flowOf(true),
                ).toList()
            assertEquals(
                listOf(SyncStartup.Starting(Synchronizer.Status.INITIALIZING), SyncStartup.Idle),
                states,
            )
        }
}
