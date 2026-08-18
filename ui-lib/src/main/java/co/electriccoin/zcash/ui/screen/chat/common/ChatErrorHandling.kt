// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.common

import co.electriccoin.zcash.spackle.Twig
import kotlin.coroutines.cancellation.CancellationException

internal inline fun runChatCall(message: String, block: () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception
    ) {
        Twig.warn(e) { message }
    }
}

internal inline fun <T> runChatCallResult(message: String, block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception
    ) {
        Twig.warn(e) { message }
        Result.failure(e)
    }
