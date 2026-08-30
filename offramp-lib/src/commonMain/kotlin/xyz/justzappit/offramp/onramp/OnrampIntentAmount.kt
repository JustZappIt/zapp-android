// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.PaymentQrParseResult
import xyz.justzappit.offramp.p2p.PaymentQrParser
import xyz.justzappit.offramp.p2p.Usdc6

/**
 * Checks the amount a payment request will charge against the order it belongs to.
 *
 * The app hands the user a payload it did not build, so nothing else guarantees the two agree. A
 * units slip on either side turns ₹105 into ₹104,999,902, which a payment app rejects as a limit
 * breach rather than as a malformed request — an error that reads like the user's bank refusing
 * them.
 */
object OnrampIntentAmount {
    /**
     * What the request will charge, or null when it declares nothing parseable. Parsing goes
     * through [PaymentQrParser] so every corridor is covered by its own rail's reader rather than
     * only `upi://`. No resolver is passed: a dynamic PIX QR carries no static amount to disagree
     * with, and a display-time check must not reach the network.
     */
    suspend fun declaredAmount(
        currency: CurrencyCode,
        instruction: OnrampPaymentInstruction,
    ): Usdc6? =
        payloadOf(instruction)
            ?.let { PaymentQrParser.parse(currency, it) as? PaymentQrParseResult.Success }
            ?.parsed
            ?.fiatAmount
            ?.let(Usdc6::ofWhole)

    /**
     * True when the request charges something other than the order's amount, beyond the rounding a
     * two-decimal payload legitimately introduces. An undeclared amount is not a mismatch: the user
     * types it into the payment app themselves.
     */
    suspend fun disagreesWith(
        currency: CurrencyCode,
        instruction: OnrampPaymentInstruction,
        expected: Usdc6,
    ): Boolean {
        val declared = declaredAmount(currency, instruction) ?: return false
        val difference = if (declared > expected) declared - expected else expected - declared
        return difference > TOLERANCE
    }

    private fun payloadOf(instruction: OnrampPaymentInstruction): String? =
        when (instruction) {
            is OnrampPaymentInstruction.Upi -> instruction.intentUrl
            is OnrampPaymentInstruction.Qr -> instruction.payload
            is OnrampPaymentInstruction.Fields -> instruction.qrPayload
            else -> null
        }

    // One whole currency unit: loose enough for the rounding any rail's payload applies, far
    // tighter than the thousand-fold error a micros/whole-units slip produces.
    private const val TOLERANCE_MICROS = 1_000_000L

    private val TOLERANCE = Usdc6.ofMicros(TOLERANCE_MICROS)
}
