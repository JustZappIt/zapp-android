// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.advancedsettings.debug.atomicswap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRunState
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRunner
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class DebugAtomicSwapVM(
    private val runner: AtomicSwapRunner,
    private val copyToClipboardUseCase: CopyToClipboardUseCase,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    val state: StateFlow<DebugAtomicSwapState> =
        runner.state
            .map(::createState)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = createState(runner.state.value),
            )

    private fun createState(run: AtomicSwapRunState): DebugAtomicSwapState {
        val record = run.record
        return DebugAtomicSwapState(
            status =
                listOfNotNull(
                    run.busy?.let { "running: $it" },
                    run.waiting?.let { "waiting: $it" },
                    run.railgunAddress?.let { "payouts go to ${it.take(ADDRESS_PREFIX)}…" },
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
                ).ifEmpty { listOf("no swap yet") },
            activity = run.activity,
            error = run.error,
            isBusy = run.busy != null,
            onOpen = { runner.open(units = 1) },
            onDeposit = runner::deposit,
            onAdvance = runner::advance,
            onAbandon = runner::abandon,
            onCopySwapId = { record?.let { copyToClipboardUseCase(it.swapId, isSensitive = false) } },
            onBack = navigationRouter::back,
        )
    }

    private companion object {
        const val ADDRESS_PREFIX = 24
        const val ID_PREFIX = 18
    }
}
