// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.Synchronizer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Where the wallet's sync engine is on its way to first contact with the server, as the
 * Home tab's "Connecting" chip sees it.
 */
internal sealed interface SyncStartup {
    /** No wallet, or the current engine has already reached the server once. Nothing to watch. */
    data object Idle : SyncStartup

    /** A wallet is persisted but no engine has been handed out for it. */
    data object AwaitingEngine : SyncStartup

    /**
     * [engine] exists but has not reported [Synchronizer.Status.SYNCING] or later yet. Carrying the
     * engine makes a rebuilt one a new state, so it gets a window of its own.
     */
    data class Starting(
        val engine: Synchronizer,
        val status: Synchronizer.Status,
    ) : SyncStartup
}

/**
 * How long "Connecting" may last before it counts as stuck. A fresh wallet normally leaves it
 * within seconds; a slow device on Tor can take tens of seconds, so this leaves a wide margin.
 */
internal val SYNC_STARTUP_STALL_TIMEOUT: Duration = 90.seconds

/**
 * Maps the engine and wallet flows to [SyncStartup]. Each engine is watched only until it first
 * leaves startup: a later STOPPED (a migration pause, a closing engine) is not a startup stall.
 *
 * Without a network every state counts as [SyncStartup.Idle], so no window runs offline and no
 * reset is spent there; when the network returns, a still-stuck engine gets a fresh window.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun syncStartupStates(
    synchronizer: Flow<Synchronizer?>,
    hasWallet: Flow<Boolean>,
    hasNetwork: Flow<Boolean>,
): Flow<SyncStartup> =
    combine(
        synchronizer.flatMapLatest { engine ->
            if (engine == null) {
                hasWallet.map { if (it) SyncStartup.AwaitingEngine else SyncStartup.Idle }
            } else {
                engine.status
                    .map { status ->
                        if (status.isStartup()) SyncStartup.Starting(engine, status) else SyncStartup.Idle
                    }.transformWhile { state ->
                        emit(state)
                        state != SyncStartup.Idle
                    }
            }
        },
        hasNetwork,
    ) { state, online -> if (online) state else SyncStartup.Idle }

/**
 * Calls [onStall] whenever one [SyncStartup] state other than [SyncStartup.Idle] has lasted for
 * [timeout]. Any change of state restarts the clock, so a reset that rebuilds the engine gets a
 * full window of its own.
 */
internal suspend fun watchSyncStartup(
    states: Flow<SyncStartup>,
    timeout: Duration = SYNC_STARTUP_STALL_TIMEOUT,
    onStall: suspend (SyncStartup) -> Unit,
) {
    states.distinctUntilChanged().collectLatest { state ->
        if (state != SyncStartup.Idle) {
            delay(timeout)
            onStall(state)
        }
    }
}

private fun Synchronizer.Status.isStartup() =
    this == Synchronizer.Status.INITIALIZING || this == Synchronizer.Status.STOPPED
