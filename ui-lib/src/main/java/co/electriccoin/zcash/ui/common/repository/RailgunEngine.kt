// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.repository

import co.electriccoin.zcash.ui.common.bestEffort
import co.electriccoin.zcash.ui.common.provider.RailgunKeyProvider
import co.electriccoin.zcash.ui.common.provider.RailgunMnemonicProvider
import co.electriccoin.zcash.ui.common.security.AUTHENTICATE_TIMEOUT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
 * follows the app's lock as well as its uses ([onAppUnlocked]): a lock that lasts the authentication gate's timeout
 * closes it.
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

    // The app's lock as reported, and whether the page was closed for it: then nothing runs until the unlock.
    private val appLock = MutableStateFlow(AppLock.UNLOCKED)

    // The grace before that close; not under [work], whose children the close cancels.
    @Volatile
    private var lockClose: Job? = null

    suspend fun <T> withSession(block: suspend (RailgunSession) -> T): T =
        scope
            .async(work) {
                check(!isReset) { "the wallet is being reset" }
                // Before the mutex, never under it: a reset or a wallet change mustn't wait on the app's lock.
                appLock.first { it != AppLock.CLOSED }
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
     * The app locked ([isUnlocked] false: it left the foreground, or its authentication gate is up) or unlocked. A
     * lock that lasts [AUTHENTICATE_TIMEOUT], after which the app asks to authenticate again, stops the uses in
     * flight as a reset does and closes the page, which drops the wallet's keys; a new use then waits for the unlock,
     * which opens the page again. A shorter lock leaves the page to its uses, so a sync carries on across a short
     * background.
     */
    fun onAppUnlocked(isUnlocked: Boolean) {
        if (isUnlocked) {
            lockClose?.cancel()
            appLock.value = AppLock.UNLOCKED
        } else if (appLock.compareAndSet(AppLock.UNLOCKED, AppLock.LOCKED)) {
            lockClose = scope.launch { closeForLock() }
        }
    }

    /**
     * Stops every use and deletes what the engine stored, as far as the WebView lets it: what's left is
     * encrypted with a key the reset deletes. Nothing runs again until the wallet changes.
     */
    suspend fun reset() {
        isReset = true
        // A lock under way has nothing left to close; the page stays closed until the unlock all the same.
        lockClose?.cancel()
        appLock.compareAndSet(AppLock.LOCKED, AppLock.CLOSED)
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
    private suspend fun closeForLock() {
        delay(AUTHENTICATE_TIMEOUT)
        if (!appLock.compareAndSet(AppLock.LOCKED, AppLock.CLOSED)) return
        // An unlock meanwhile can't leave this half done: the page closes, and its next use opens it again.
        withContext(NonCancellable) {
            work.cancelChildren()
            lock.withLock {
                idleClose?.cancel()
                closePage()
            }
        }
    }

    // Under the lock.
    private suspend fun closePage() {
        wallet.close()
        state.update {
            it.copy(phase = RailgunWalletState.Phase.IDLE, utxoScan = null, txidScan = null, proof = null)
        }
    }

    private enum class AppLock { UNLOCKED, LOCKED, CLOSED }

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
