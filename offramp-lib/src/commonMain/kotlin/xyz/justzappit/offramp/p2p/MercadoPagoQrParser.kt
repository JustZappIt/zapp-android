// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

/**
 * Parses an Argentine MercadoPago / Transferencias-3.0 (EMVCo MPM) merchant QR. Byte-compatible with
 * `@p2pdotme/sdk` v1.2.4 (`qr-parsers/parsers/ars.ts`): gate on the ARS currency tag (`5303032`) OR
 * the AR country tag (`5802AR`), verify the EMVCo CRC (tag 63), then take the merchant name (tag 59,
 * or `"Unknown"`). ARS merchant QRs never fix a payer amount, so [ParsedPaymentQr.fiatAmount] is
 * always null and — unlike QRIS — a missing merchant name is not an error.
 */
object MercadoPagoQrParser {
    @Suppress("ReturnCount")
    fun parse(qrData: String): PaymentQrParseResult {
        if (qrData.isBlank()) return PaymentQrParseResult.Failure(PaymentQrError.EmptyQr)
        val trimmed = qrData.trim()

        if (!trimmed.contains(ARS_CURRENCY_TAG) && !trimmed.contains(AR_COUNTRY_TAG)) {
            return PaymentQrParseResult.Failure(PaymentQrError.InvalidFormat)
        }
        if (!EmvQr.verifyCrc16(trimmed)) {
            return PaymentQrParseResult.Failure(PaymentQrError.InvalidChecksum)
        }

        val merchantName = EmvQr.extractTags(trimmed, setOf(TAG_MERCHANT_NAME))[TAG_MERCHANT_NAME] ?: MERCHANT_UNKNOWN
        return PaymentQrParseResult.Success(ParsedPaymentQr(merchantName, null))
    }

    private const val ARS_CURRENCY_TAG = "5303032"
    private const val AR_COUNTRY_TAG = "5802AR"
    private const val TAG_MERCHANT_NAME = "59"
    private const val MERCHANT_UNKNOWN = "Unknown"
}
