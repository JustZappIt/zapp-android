// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.repository

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.bestEffort
import co.electriccoin.zcash.ui.common.provider.RailgunKeyProvider
import co.electriccoin.zcash.ui.common.provider.RailgunMnemonicProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.justzappit.railgun.RailgunNetwork
import xyz.justzappit.railgun.RailgunSession
import xyz.justzappit.railgun.RailgunWallet
import kotlin.time.Duration.Companion.minutes

/** The engine, one use at a time, each run in [scope] to its end so no caller frees it while the page works. */
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

    suspend fun <T> withSession(block: suspend (RailgunSession) -> T): T =
        scope
            .async(work) {
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
     * Stops every use and deletes what the engine stored, as far as the WebView lets it: what's left is
     * encrypted with a key the reset deletes. Nothing runs again until the wallet changes.
     */
    suspend fun reset() {
        isReset = true
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
        keyProvider.retireZcashSeedWallet { wallet.forgetLegacyStorage() }
        val expected = mnemonicProvider.address()
        val key = keyProvider.encryptionKey()
        val gasAccountKey = if (network == RailgunNetwork.SEPOLIA) keyProvider.gasAccountKey() else null
        val session =
            try {
                mnemonicProvider.withMnemonic { wallet.open(network, key, it, gasAccountKey) }
            } finally {
                key.fill(0)
                gasAccountKey?.fill(0)
            }
        if (session.address != expected) {
            wallet.close()
            error("the engine opened ${session.address}, not the Railgun mnemonic's $expected")
        }
        session.gasAccountAddress?.let { Twig.info { "Railgun gas account: $it" } }
        state.update {
            it.copy(phase = RailgunWalletState.Phase.READY, address = session.address, fees = session.fees)
        }
        return session
    }

    private suspend fun closeWhenIdle() {
        delay(IDLE_CLOSE)
        lock.withLock {
            wallet.close()
            state.update {
                it.copy(phase = RailgunWalletState.Phase.IDLE, utxoScan = null, txidScan = null, proof = null)
            }
        }
    }

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
