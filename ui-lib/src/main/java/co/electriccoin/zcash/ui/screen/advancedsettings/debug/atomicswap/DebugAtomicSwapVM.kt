// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.advancedsettings.debug.atomicswap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.exception.SdkException
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapKeysImpl
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapState
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.justzappit.atomicswap.AtomicSwap
import xyz.justzappit.atomicswap.AtomicSwapException
import xyz.justzappit.evm.rpc.RpcException
import xyz.justzappit.offramp.atomicswap.AtomicSwapHttpException
import java.io.IOException

class DebugAtomicSwapVM(
    private val repository: AtomicSwapRepository,
    private val keys: AtomicSwapKeysImpl,
    private val copyToClipboardUseCase: CopyToClipboardUseCase,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val log = MutableStateFlow(Log())

    val state: StateFlow<DebugAtomicSwapState> =
        combine(repository.state, log, ::createState)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = createState(repository.state.value, log.value),
            )

    init {
        viewModelScope.launch {
            val address = keys.withKey(0) { AtomicSwap.railgunAddress(it.seed) }
            log.update { it.copy(railgunAddress = address) }
        }
        repository.resume(isForeground = true)
    }

    private fun createState(
        swap: AtomicSwapState,
        log: Log
    ): DebugAtomicSwapState {
        val record = swap.record
        return DebugAtomicSwapState(
            status = status(swap, log).ifEmpty { listOf("no swap yet") },
            activity = log.lines,
            error = log.error,
            isBusy = log.busy != null,
            onOpen = { act("open") { open() } },
            onAdvance = {
                repository.resume(isForeground = true)
                repository.retryNow()
            },
            onAbandon = {
                act("abandon") {
                    repository.abandon()
                    "abandoned"
                }
            },
            onCopySwapId = { record?.let { copyToClipboardUseCase(it.swapId, isSensitive = false) } },
            onBack = navigationRouter::back,
        )
    }

    private fun status(
        swap: AtomicSwapState,
        log: Log
    ): List<String> {
        val record = swap.record
        return listOfNotNull(
            log.busy?.let { "running: $it" },
            swap.activity?.let { "doing: ${it.name.lowercase()}" },
            swap.wait?.let { "waiting: ${it.reason.name.lowercase()}" },
            swap.confirmations?.let { "confirmations: $it" },
            swap.problem?.let { "problem: ${it.name.lowercase()}" },
            log.railgunAddress?.let { "payouts go to ${it.take(ADDRESS_PREFIX)}…" },
            record?.let { "swap #${it.index}: ${it.swapId.take(ID_PREFIX)}…" },
            record?.let { "quote: ${it.quote.depositZat} zat for ${it.quote.amount} token base units" },
            record?.let {
                when {
                    it.depositTxId != null -> "deposit: ${it.depositTxId?.take(ID_PREFIX)}…"
                    it.depositAttempted -> "deposit: started, no transaction id"
                    else -> "deposit: not yet"
                }
            },
            record?.outcome?.let { "outcome: $it" },
        )
    }

    private suspend fun open(): String {
        val quote = repository.quote(units = 1)
        val record = repository.accept(quote.offer)
        return "swap #${record.index} accepted for ${record.quote.depositZat} zat, fee ${quote.depositFeeZat}"
    }

    private fun act(
        name: String,
        block: suspend () -> String
    ) {
        if (log.value.busy != null) return
        log.update { it.copy(busy = name, error = null) }
        viewModelScope.launch {
            val line =
                try {
                    "$name: ${block()}"
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
                } catch (e: IllegalStateException) {
                    fail(name, e)
                } catch (e: IllegalArgumentException) {
                    fail(name, e)
                }
            log.update { it.copy(busy = null, lines = it.lines + line) }
        }
    }

    private fun fail(
        name: String,
        e: Exception
    ): String {
        Twig.warn(e) { "Atomic swap: $name failed" }
        log.update { it.copy(error = "$name: ${e.message ?: e::class.simpleName}") }
        return "$name failed"
    }

    private data class Log(
        val railgunAddress: String? = null,
        val busy: String? = null,
        val lines: List<String> = emptyList(),
        val error: String? = null,
    )

    private companion object {
        const val ADDRESS_PREFIX = 24
        const val ID_PREFIX = 18
    }
}
