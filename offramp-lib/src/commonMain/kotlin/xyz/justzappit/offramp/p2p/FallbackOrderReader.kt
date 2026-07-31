// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import kotlinx.coroutines.CancellationException
import xyz.justzappit.evm.math.BigInteger

fun interface OrderReadLogger {
    fun warn(message: String, cause: Throwable?)
}

class FallbackOrderReader(
    private val primary: OrderReadSource,
    private val fallback: OrderReadSource,
    private val logger: OrderReadLogger? = null,
) : OrderReadSource {
    override suspend fun fetchOrder(orderId: BigInteger): OrderSnapshot? {
        val primaryResult = runSafely(orderId, "Primary", primary)
        if (primaryResult != null) return primaryResult
        return runSafely(orderId, "Fallback", fallback)
    }

    /**
     * Both layers must be total — if the fallback also throws, the orchestrator's poll loop sees
     * `null` and re-emits the WaitingFor* status without bailing. A transient RPC blip during a
     * 30-minute completion wait is not allowed to fail an order whose USDC is already escrowed
     * on-chain. CancellationException always propagates so coroutine cancellation works.
     */
    private suspend fun runSafely(
        orderId: BigInteger,
        label: String,
        source: OrderReadSource,
    ): OrderSnapshot? =
        try {
            source.fetchOrder(orderId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger?.warn("$label order source failed for orderId=$orderId; continuing", e)
            null
        }
}
