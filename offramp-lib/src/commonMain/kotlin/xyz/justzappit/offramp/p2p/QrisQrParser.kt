// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

/**
 * Parses an Indonesian QRIS (EMVCo MPM) merchant QR. Byte-compatible with `@p2pdotme/sdk` v1.1.7
 * (`qr-parsers/parsers/idr.ts`): the payment address is the merchant name (tag 59), the amount is
 * optional (tag 54, payer-defined when absent), and a present-but-unparseable amount is a hard
 * error — unlike PIX, which drops it. The EMVCo CRC (tag 63) is left unverified to mirror the SDK's
 * `parseQRIS` (PIX, by contrast, checks it).
 */
object QrisQrParser {
    fun parse(qrData: String): PaymentQrParseResult {
        if (qrData.isBlank()) return PaymentQrParseResult.Failure(PaymentQrError.EmptyQr)

        val tags =
            EmvQr.extractTags(
                qrData.trim(),
                setOf(TAG_PAYLOAD_FORMAT, TAG_CURRENCY, TAG_COUNTRY, TAG_AMOUNT, TAG_MERCHANT_NAME),
            )
        if (tags[TAG_PAYLOAD_FORMAT] != EXPECTED_PAYLOAD_FORMAT ||
            tags[TAG_CURRENCY] != EXPECTED_CURRENCY ||
            tags[TAG_COUNTRY]?.let { it != EXPECTED_COUNTRY } == true
        ) {
            return PaymentQrParseResult.Failure(PaymentQrError.InvalidFormat)
        }
        val merchantName = tags[TAG_MERCHANT_NAME]
        val amountStr = tags[TAG_AMOUNT]
        val fiatAmount = amountStr?.let { parsePositiveFiatAmount(it) }

        return when {
            merchantName == null -> {
                PaymentQrParseResult.Failure(PaymentQrError.MissingPaymentAddress)
            }

            amountStr != null && fiatAmount == null -> {
                PaymentQrParseResult.Failure(PaymentQrError.InvalidAmount(amountStr))
            }

            else -> {
                PaymentQrParseResult.Success(ParsedPaymentQr(merchantName, fiatAmount))
            }
        }
    }

    private const val TAG_AMOUNT = "54"
    private const val TAG_MERCHANT_NAME = "59"
    private const val TAG_PAYLOAD_FORMAT = "00"
    private const val TAG_CURRENCY = "53"
    private const val TAG_COUNTRY = "58"
    private const val EXPECTED_PAYLOAD_FORMAT = "01"
    private const val EXPECTED_CURRENCY = "360"
    private const val EXPECTED_COUNTRY = "ID"
}
