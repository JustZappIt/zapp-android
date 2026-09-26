// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import cash.z.ecc.android.sdk.exception.SdkException
import co.electriccoin.zcash.spackle.Twig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.justzappit.atomicswap.AtomicSwap
import xyz.justzappit.atomicswap.AtomicSwapException
import xyz.justzappit.evm.rpc.RpcException
import xyz.justzappit.offramp.atomicswap.AtomicSwapDriver
import xyz.justzappit.offramp.atomicswap.AtomicSwapHttpException
import xyz.justzappit.offramp.atomicswap.AtomicSwapRecord
import xyz.justzappit.offramp.atomicswap.AtomicSwapStep
import xyz.justzappit.offramp.atomicswap.AtomicSwapStore
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

data class AtomicSwapRunState(
    val record: AtomicSwapRecord? = null,
    val railgunAddress: String? = null,
    val busy: String? = null,
    val waiting: String? = null,
    val activity: List<String> = emptyList(),
    val error: String? = null,
)

/** Runs the swap's steps one at a time, off the screen's lifecycle, for the debug screen. */
class AtomicSwapRunner(
    private val driver: AtomicSwapDriver,
    private val store: AtomicSwapStore,
    private val keys: AtomicSwapKeysImpl,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mutableState = MutableStateFlow(AtomicSwapRunState())
    val state: StateFlow<AtomicSwapRunState> = mutableState.asStateFlow()
    private var job: Job? = null

    init {
        scope.launch {
            val railgunAddress = keys.withKey(0) { AtomicSwap.railgunAddress(it.seed) }
            mutableState.update { it.copy(record = store.active(), railgunAddress = railgunAddress) }
        }
    }

    fun open(units: Int) =
        run("open") {
            val record = driver.open(units)
            mutableState.update { it.copy(record = record) }
            "swap #${record.index} accepted for ${record.quote.depositZat} zat: ${record.swapId}"
        }

    fun deposit() =
        run("deposit") {
            val record = driver.deposit(requireRecord())
            mutableState.update { it.copy(record = record) }
            "paid ${record.quote.depositZat} zat to the deposit account: ${record.depositTxId}"
        }

    /** Looks at the chain every [POLL] until the swap finishes, claiming or refunding when it can. */
    fun advance() =
        run("advance") {
            var step = driver.advance(requireRecord())
            while (step is AtomicSwapStep.Waiting) {
                val reason = step.reason
                mutableState.update { it.copy(waiting = reason) }
                delay(POLL)
                step = driver.advance(requireRecord())
            }
            mutableState.update { it.copy(record = store.active(), waiting = null) }
            (step as AtomicSwapStep.Finished).outcome
        }

    fun abandon() =
        run("abandon") {
            val step = driver.abandon(requireRecord()) as AtomicSwapStep.Finished
            mutableState.update { it.copy(record = store.active()) }
            step.outcome
        }

    private suspend fun requireRecord() = checkNotNull(store.active()) { "no swap yet" }

    private fun run(
        name: String,
        block: suspend () -> String
    ) {
        synchronized(this) {
            if (job?.isActive == true) return
            job =
                scope.launch {
                    mutableState.update { it.copy(busy = name, error = null) }
                    val line =
                        try {
                            "$name: ${block()}"
                        } catch (e: IllegalStateException) {
                            fail(name, e)
                        } catch (e: IllegalArgumentException) {
                            fail(name, e)
                        } catch (e: AtomicSwapHttpException) {
                            fail(name, e)
                        } catch (e: AtomicSwapException) {
                            fail(name, e)
                        } catch (e: RpcException) {
                            fail(name, e)
                        } catch (e: SdkException) {
                            fail(name, e)
                        } catch (e: IOException) {
                            fail(name, e)
                        } catch (e: TimeoutCancellationException) {
                            fail(name, e)
                        }
                    mutableState.update { it.copy(busy = null, waiting = null, activity = it.activity + line) }
                }
        }
    }

    private fun fail(
        name: String,
        e: Exception
    ): String {
        Twig.warn(e) { "Atomic swap: $name failed" }
        mutableState.update { it.copy(error = "$name: ${e.message ?: e::class.simpleName}") }
        return "$name failed"
    }

    private companion object {
        val POLL = 20.seconds
    }
}
