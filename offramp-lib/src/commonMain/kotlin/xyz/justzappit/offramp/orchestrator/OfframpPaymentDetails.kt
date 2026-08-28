// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.orchestrator

import xyz.justzappit.evm.math.BigDecimal
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.offramp.p2p.OrderSnapshot

/**
 * Merchant payment details captured after a PAY order is accepted.
 *
 * [rawPayload] is the exact scanned QR payload to sign/encrypt for setSellOrderUpi: validated, but
 * submitted verbatim rather than re-serialised, which is also what `@p2pdotme/sdk`'s parsers hand
 * back. The merchant decodes what their rail issued, so re-serialising is a chance to differ.
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
