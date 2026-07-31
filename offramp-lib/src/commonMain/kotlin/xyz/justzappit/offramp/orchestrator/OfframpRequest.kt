// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.orchestrator

import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6

data class OfframpRequest(
    /**
     * Legacy/manual path recipient. Scan & Pay orders leave this blank until the merchant accepts,
     * then provide the scanned QR payload through [OfframpPaymentDetailsProvider].
     */
    val recipientUpi: String = "",
    val usdcAmount: Usdc6,
    /**
     * User-quoted fiat in 6-decimal micros (e.g. `37_280_000` ⇒ ₹37.28). For Scan & Pay this is
     * the amount used to place the PAY order; the accepted merchant receives the scanned QR payload
     * later through [OfframpPaymentDetailsProvider].
     */
    val fiatAmount: Usdc6,
    val currency: CurrencyCode = CurrencyCode.Inr,
    /** Optional `pn=` display name. */
    val payeeName: String? = null,
    /** `placeOrder._fiatAmountLimit`: contract slippage floor. Null/ZERO disables it. */
    val fiatAmountLimit: Usdc6? = null,
) {
    init {
        require(usdcAmount > Usdc6.ZERO) { "usdcAmount must be positive" }
        require(fiatAmount > Usdc6.ZERO) { "fiatAmount must be positive" }
    }
}
