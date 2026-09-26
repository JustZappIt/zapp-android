// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.advancedsettings.debug.atomicswap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class DebugAtomicSwapVM(
    private val spike: AtomicSwapRefundSpike,
    private val copyToClipboardUseCase: CopyToClipboardUseCase,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    val state: StateFlow<DebugAtomicSwapState> =
        spike.state
            .map(::createState)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = createState(spike.state.value),
            )

    private fun createState(spike: AtomicSwapSpikeState) =
        DebugAtomicSwapState(
            status =
                listOfNotNull(
                    spike.busy?.let { "running: $it" },
                    spike.birthday?.let { "birthday: $it" },
                    "deposit account imported: ${if (spike.imported) "yes" else "no"}",
                    spike.depositBalance?.let { "deposit: $it" },
                ),
            depositAddress = spike.depositAddress,
            onCopyAddress = { spike.depositAddress?.let { copyToClipboardUseCase(it, isSensitive = false) } },
            activity = spike.activity,
            error = spike.error,
            isBusy = spike.busy != null,
            onPrepare = this.spike::prepare,
            onImport = this.spike::import,
            onSweep = this.spike::sweep,
            onDelete = this.spike::delete,
            onBack = navigationRouter::back,
        )
}
