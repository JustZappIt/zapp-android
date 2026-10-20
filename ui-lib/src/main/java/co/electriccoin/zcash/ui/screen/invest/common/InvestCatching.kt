package co.electriccoin.zcash.ui.screen.invest.common

import kotlinx.coroutines.CancellationException

/** [runCatching] that lets coroutine cancellation through. */
@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> investCatching(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
