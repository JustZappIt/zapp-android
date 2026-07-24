package co.electriccoin.zcash.work

import cash.z.ecc.android.sdk.TransferResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MigrationWorkerTest {
    @Test
    fun `a Success on the first attempt does not retry`() = runTest {
        var callCount = 0
        val result = executeWithRetries(retryDelayMs = 0) {
            callCount++
            TransferResult.Success("txid")
        }

        assertIs<TransferResult.Success>(result)
        assertEquals(1, callCount)
    }

    @Test
    fun `a retryable NetworkError retries up to maxAttempts then stops`() = runTest {
        var callCount = 0
        val result = executeWithRetries(maxAttempts = 3, retryDelayMs = 0) {
            callCount++
            TransferResult.NetworkError(retryable = true)
        }

        assertIs<TransferResult.NetworkError>(result)
        assertEquals(3, callCount)
    }

    @Test
    fun `a non-retryable NetworkError stops immediately without exhausting maxAttempts`() = runTest {
        var callCount = 0
        val result = executeWithRetries(maxAttempts = 3, retryDelayMs = 0) {
            callCount++
            TransferResult.NetworkError(retryable = false)
        }

        assertIs<TransferResult.NetworkError>(result)
        assertEquals(1, callCount)
    }

    @Test
    fun `a null result (nothing due yet) stops immediately without retrying`() = runTest {
        var callCount = 0
        val result = executeWithRetries(maxAttempts = 3, retryDelayMs = 0) {
            callCount++
            null
        }

        assertEquals(null, result)
        assertEquals(1, callCount)
    }

    @Test
    fun `a retryable NetworkError that later succeeds stops as soon as it succeeds`() = runTest {
        var callCount = 0
        val result = executeWithRetries(maxAttempts = 3, retryDelayMs = 0) {
            callCount++
            if (callCount < 2) TransferResult.NetworkError(retryable = true) else TransferResult.Success("txid")
        }

        assertIs<TransferResult.Success>(result)
        assertEquals(2, callCount)
    }
}
