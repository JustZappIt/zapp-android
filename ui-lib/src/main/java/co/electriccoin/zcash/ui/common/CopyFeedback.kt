// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds the most recently copied value for a couple of seconds so a card can show a check.
 *
 * A second tap restarts the window rather than inheriting the first tap's timer.
 */
class CopyFeedback(
    private val scope: CoroutineScope,
) {
    private val value = MutableStateFlow<String?>(null)

    val copiedValue: StateFlow<String?> = value.asStateFlow()

    private var resetJob: Job? = null

    fun mark(copied: String) {
        value.value = copied
        resetJob?.cancel()
        resetJob =
            scope.launch {
                delay(FEEDBACK_MS)
                value.value = null
            }
    }

    fun cancel() {
        resetJob?.cancel()
    }

    private companion object {
        const val FEEDBACK_MS = 2_000L
    }
}
