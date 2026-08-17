// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.orchestrator

import xyz.justzappit.evm.math.BigDecimal
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.offramp.p2p.OrderSnapshot

/**
 * Merchant payment details captured after a PAY order is accepted.
 *
 * [rawPayload] is the exact scanned QR payload to sign/encrypt for setSellOrderUpi. This mirrors the
 * p2p.me web client, which validates the QR but submits the original scanned string.
 */
data class OfframpPaymentDetails(
    val rawPayload: String,
    val paymentAddress: String,
    val fiatAmount: BigDecimal? = null,
)

fun interface OfframpPaymentDetailsProvider {
    suspend fun requestPaymentDetails(
        orderId: BigInteger,
        accepted: OrderSnapshot,
        request: OfframpRequest,
    ): OfframpPaymentDetails
}
