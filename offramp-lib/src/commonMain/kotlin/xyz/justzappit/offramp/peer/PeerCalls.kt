// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import kotlinx.coroutines.CancellationException

/**
 * `runCatching` for a suspending call. The stdlib one swallows the [CancellationException] that
 * leaving a screen throws, letting the body run on inside a dead coroutine: the failure path of that
 * is a second transaction against an order the first one had already handled.
 */
internal inline fun <T> runPeerCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception
    ) {
        Result.failure(e)
    }
