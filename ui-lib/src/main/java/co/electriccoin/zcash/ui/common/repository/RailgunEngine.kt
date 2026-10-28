// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.repository

import co.electriccoin.zcash.ui.common.bestEffort
import co.electriccoin.zcash.ui.common.provider.RailgunKeyProvider
import co.electriccoin.zcash.ui.common.provider.RailgunMnemonicProvider
import co.electriccoin.zcash.ui.common.security.AUTHENTICATE_TIMEOUT
import co.electriccoin.zcash.ui.common.security.AppLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.justzappit.railgun.RailgunNetwork
import xyz.justzappit.railgun.RailgunSession
import xyz.justzappit.railgun.RailgunWallet
import kotlin.time.Duration.Companion.minutes

/**
 * The engine, one use at a time, each run in [scope] to its end so no caller frees it while the page works. The page
 * follows the app's lock as well as its uses ([onAppLock]): the authentication gate up closes it at once, and a lock
 * in the background once it has lasted the gate's timeout.
 */
internal class RailgunEngine(
    private val wallet: RailgunWallet,
    private val keyProvider: RailgunKeyProvider,
    private val mnemonicProvider: RailgunMnemonicProvider,
    private val network: RailgunNetwork,
    private val state: MutableStateFlow<RailgunWalletState>,
    private val scope: CoroutineScope,
) {
    private val work = SupervisorJob(scope.coroutineContext.job)
    private val lock = Mutex()

    // Under the lock.
    private var idleClose: Job? = null

    @Volatile
    private var isReset = false

    // Whether a background lock's grace is under way, or the page is closed for the app's lock: then nothing runs
    // until the unlock.
    private val lockState = MutableStateFlow(LockState.UNLOCKED)

    // The grace; not under [work], whose children the close cancels.
    @Volatile
    private var grace: Job? = null

    suspend fun <T> withSession(block: suspend (RailgunSession) -> T): T =
        scope
            .async(work) {
                check(!isReset) { "the wallet is being reset" }
                // Before the mutex, never under it: a reset or a wallet change mustn't wait on the app's lock.
                lockState.first { it != LockState.CLOSED }
                lock.withLock {
                    check(!isReset) { "the wallet is being reset" }
                    idleClose?.cancel()
                    try {
                        block(open())
                    } finally {
                        if (!isReset) idleClose = scope.launch(work) { closeWhenIdle() }
                    }
                }
            }.await()

    /**
     * [withSession] for a call the page answers at once, whatever else it runs: on a page another use holds open, it
     * runs without waiting for that use to end. Never for a call that needs the wallet synced, or proves. Should that
     * use close the page meanwhile, it fails as the page's calls then do, and may be made again.
     */
    suspend fun <T> withSessionAlongside(block: suspend (RailgunSession) -> T): T {
        val held = wallet.session?.takeIf { lock.isLocked } ?: return withSession(block)
        return scope
            .async(work) {
                check(!isReset) { "the wallet is being reset" }
                block(held)
            }.await()
    }

    /**
     * The app's lock changed to [lock]. The gate up ([AppLock.GATE_UP]) closes the page at once, stopping the uses in
     * flight as a reset does, which drops the wallet's keys. A lock in the background ([AppLock.LOCKED]) does the same
     * once it has lasted [AUTHENTICATE_TIMEOUT], after which the app would ask to authenticate again, and leaves the
     * page to its uses meanwhile, so a sync carries on across a short background. Once closed, a new use waits for the
     * unlock ([AppLock.UNLOCKED]), which opens the page again.
     */
    fun onAppLock(lock: AppLock) {
        when (lock) {
            AppLock.UNLOCKED -> {
                grace?.cancel()
                lockState.value = LockState.UNLOCKED
            }

            AppLock.LOCKED -> {
                if (lockState.compareAndSet(LockState.UNLOCKED, LockState.GRACE)) {
                    grace = scope.launch { closeAfterGrace() }
                }
            }

            AppLock.GATE_UP -> {
                grace?.cancel()
                if (lockState.getAndUpdate { LockState.CLOSED } != LockState.CLOSED) scope.launch { closeForLock() }
            }
        }
    }

    /**
     * Stops every use and deletes what the engine stored, as far as the WebView lets it: what's left is
     * encrypted with a key the reset deletes. Nothing runs again until the wallet changes.
     */
    suspend fun reset() {
        isReset = true
        // A grace under way has nothing left to close; the page stays closed until the unlock all the same.
        grace?.cancel()
        lockState.compareAndSet(LockState.GRACE, LockState.CLOSED)
        work.cancelChildren()
        work.children.forEach { it.join() }
        lock.withLock {
            bestEffort("Railgun: the engine's storage wasn't wiped") { wallet.wipe() }
            state.update { it.forgotten() }
        }
    }

    /** Closes the engine once whatever runs for the wallet before is done. */
    suspend fun forget() =
        lock.withLock {
            idleClose?.cancel()
            wallet.close()
            isReset = false
            state.update { it.forgotten() }
        }

    private suspend fun open(): RailgunSession {
        wallet.session?.let { return it }
        state.update { it.copy(phase = RailgunWalletState.Phase.STARTING) }
        val expected = mnemonicProvider.address()
        val key = keyProvider.encryptionKey()
        val session =
            try {
                mnemonicProvider.withMnemonic { wallet.open(network, key, it) }
            } finally {
                key.fill(0)
            }
        if (session.address != expected) {
            wallet.close()
            error("the engine opened ${session.address}, not the Railgun mnemonic's $expected")
        }
        state.update {
            it.copy(phase = RailgunWalletState.Phase.READY, address = session.address, fees = session.fees)
        }
        return session
    }

    private suspend fun closeWhenIdle() {
        delay(IDLE_CLOSE)
        lock.withLock { closePage() }
    }

    // The grace is the gate's timeout: an app away for longer authenticates again before anything of its shows.
    private suspend fun closeAfterGrace() {
        delay(AUTHENTICATE_TIMEOUT)
        if (lockState.compareAndSet(LockState.GRACE, LockState.CLOSED)) closeForLock()
    }

    // Once the page is closed for the lock: the uses in flight stop, as in a reset, and the page closes. Never cut
    // short: an unlock meanwhile finds the page closed, and its next use opens it again.
    private suspend fun closeForLock() =
        withContext(NonCancellable) {
            work.cancelChildren()
            lock.withLock {
                idleClose?.cancel()
                closePage()
            }
        }

    // Under the lock. With no page open there is nothing to close: its keys went with it.
    private suspend fun closePage() {
        if (wallet.session != null) wallet.close()
        state.update {
            it.copy(phase = RailgunWalletState.Phase.IDLE, utxoScan = null, txidScan = null, proof = null)
        }
    }

    private enum class LockState { UNLOCKED, GRACE, CLOSED }

    private companion object {
        val IDLE_CLOSE = 10.minutes
    }
}

private fun RailgunWalletState.forgotten() =
    copy(
        phase = RailgunWalletState.Phase.IDLE,
        address = null,
        fees = null,
        sync = null,
        utxoScan = null,
        txidScan = null,
        proof = null,
        error = null,
    )
