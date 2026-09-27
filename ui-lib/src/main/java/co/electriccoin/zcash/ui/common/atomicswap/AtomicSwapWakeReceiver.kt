// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Fired around a swap's deadlines in case Android stopped the worker: starts it again, and at the last
 * call asks the user to open the app, which can always run.
 */
class AtomicSwapWakeReceiver :
    BroadcastReceiver(),
    KoinComponent {
    private val repository: AtomicSwapRepository by inject()
    private val notifier: AtomicSwapNotifier by inject()

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        val lastCall = intent.getStringExtra(AtomicSwapScheduler.EXTRA_WAKE) == AtomicSwapScheduler.Wake.LAST_CALL.name
        val pending = goAsync()
        scope.launch {
            try {
                if (repository.isUnderWay()) {
                    repository.resume(isForeground = false)
                    if (lastCall) notifier.needsYou()
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    }
}
