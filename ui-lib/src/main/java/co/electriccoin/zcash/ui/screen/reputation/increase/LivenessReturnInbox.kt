// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.reputation.increase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import xyz.justzappit.offramp.liveness.LivenessReturn

/**
 * Hands a widget redirect from [co.electriccoin.zcash.ui.MainActivity] to whichever run is waiting
 * on it. A warm process has a live run collecting; a cold one gets the route forwarded and the
 * new run drains this on start. Held until taken so neither path can miss it.
 */
class LivenessReturnInbox {
    private val pending = MutableStateFlow<LivenessReturn?>(null)

    val returns: StateFlow<LivenessReturn?> = pending.asStateFlow()

    fun put(ret: LivenessReturn) {
        pending.value = ret
    }

    fun take(): LivenessReturn? = pending.getAndUpdate { null }
}
