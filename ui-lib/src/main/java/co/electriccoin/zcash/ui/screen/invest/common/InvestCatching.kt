package co.electriccoin.zcash.ui.screen.invest.common

import kotlinx.coroutines.CancellationException

/**
 * [runCatching] for the Invest screens' suspend calls, except that coroutine cancellation still propagates:
 * leaving a screen must stop its work, not turn into an error message.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> investCatching(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
