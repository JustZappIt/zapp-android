// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.reputation.increase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import xyz.justzappit.offramp.identity.IdentityReturn

/**
 * Hands a widget redirect from [co.electriccoin.zcash.ui.MainActivity] to whichever run is waiting
 * on it. A warm process has a live run collecting; a cold one gets the route forwarded and the
 * new screen drains this on start. Held until taken so neither path can miss it.
 */
class IdentityReturnInbox {
    private val pending = MutableStateFlow<IdentityReturn?>(null)

    val returns: StateFlow<IdentityReturn?> = pending.asStateFlow()

    fun put(ret: IdentityReturn) {
        pending.value = ret
    }

    fun take(): IdentityReturn? = pending.getAndUpdate { null }
}
