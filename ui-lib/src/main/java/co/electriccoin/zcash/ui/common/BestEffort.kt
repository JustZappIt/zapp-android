// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common

import co.electriccoin.zcash.spackle.Twig
import kotlinx.coroutines.CancellationException

/**
 * Runs [block] for its effect, logging and swallowing any failure but a cancellation.
 *
 * For the writes that record something already true — a hand-off that has happened, funds that have
 * already moved — where refusing to note it down must not be reported back as the thing itself
 * having failed.
 *
 * The cancellation re-throw is the load-bearing half: `runCatching` catches
 * [CancellationException] as well, so without it a cancelled coroutine quietly completes and the
 * structured concurrency above never learns its scope is gone.
 *
 * @return whether [block] succeeded, for callers that can still tell the user something.
 */
suspend fun bestEffort(message: String, block: suspend () -> Unit): Boolean =
    runCatching { block() }
        .onFailure { throwable ->
            if (throwable is CancellationException) throw throwable
            Twig.warn(throwable) { message }
        }.isSuccess
