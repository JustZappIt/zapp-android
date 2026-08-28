// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

/**
 * Parses a Peruvian Yape/Plin QR. Byte-compatible with `@p2pdotme/sdk` v1.2.21
 * (`qr-parsers/parsers/pen.ts`). The CRC is mandatory here, unlike QRIS: Peru's `CountryOption`
 * carries a `validateQr` that the SDK's `parseQR` re-applies after parsing, so a truncated
 * screenshot must fail on both sides.
 *
 * The payment address is the entire raw payload, not a merchant name — the buyer re-renders the
 * exact QR for the merchant's bank app.
 */
object PenQrParser {
    @Suppress("ReturnCount")
    fun parse(qrData: String): PaymentQrParseResult {
        if (qrData.isBlank()) return PaymentQrParseResult.Failure(PaymentQrError.EmptyQr)
        val trimmed = qrData.trim()

        val tags = EmvQr.extractTags(trimmed, setOf(TAG_CURRENCY, TAG_COUNTRY, TAG_AMOUNT))
        if (tags[TAG_COUNTRY] != EXPECTED_COUNTRY || tags[TAG_CURRENCY] != EXPECTED_CURRENCY) {
            return PaymentQrParseResult.Failure(PaymentQrError.InvalidFormat)
        }
        if (!EmvQr.verifyCrc16(trimmed)) {
            return PaymentQrParseResult.Failure(PaymentQrError.InvalidChecksum)
        }

        val amountStr = tags[TAG_AMOUNT]
        val fiatAmount = amountStr?.let { parsePositiveFiatAmount(it) }
        return if (amountStr != null && fiatAmount == null) {
            PaymentQrParseResult.Failure(PaymentQrError.InvalidAmount(amountStr))
        } else {
            PaymentQrParseResult.Success(ParsedPaymentQr(trimmed, fiatAmount))
        }
    }

    private const val TAG_AMOUNT = "54"
    private const val TAG_CURRENCY = "53"
    private const val TAG_COUNTRY = "58"
    private const val EXPECTED_CURRENCY = "604"
    private const val EXPECTED_COUNTRY = "PE"
}
