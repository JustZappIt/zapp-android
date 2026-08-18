// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

/**
 * Parses a Nigerian NIP QR in either NIBSS NQR (EMVCo MPM) or SPD (Czech Short Payment Descriptor)
 * form. Byte-compatible with `@p2pdotme/sdk` v1.2.4 (`qr-parsers/parsers/ngn.ts`).
 *
 * - **NQR** (`parseEMVCoNQR`): verify the CRC, gate on the NIBSS AID / NGN currency tag (`5303566`) /
 *   NG country tag (`5802NG`), require the merchant name (tag 59), and hard-fail a present-but-bad
 *   amount (tag 54) the way QRIS does.
 * - **SPD** (`SPD*1.0*ACC:<nuban>*AM:<amount>*…`): the account (`ACC`) is the payment address and the
 *   `AM` amount has its thousands commas stripped before parsing. Duplicate keys are last-wins.
 */
object NgnQrParser {
    @Suppress("ReturnCount")
    fun parse(qrData: String): PaymentQrParseResult {
        if (qrData.isBlank()) return PaymentQrParseResult.Failure(PaymentQrError.EmptyQr)
        val trimmed = qrData.trim()

        if (trimmed.startsWith(SPD_PREFIX)) return parseSpd(trimmed)
        // Sanity-check this looks like EMVCo TLV before CRC-ing.
        if (EmvQr.parseTlv(trimmed).isEmpty()) {
            return PaymentQrParseResult.Failure(PaymentQrError.InvalidFormat)
        }
        return parseNqr(trimmed)
    }

    @Suppress("ReturnCount")
    private fun parseNqr(qrData: String): PaymentQrParseResult {
        if (!EmvQr.verifyCrc16(qrData)) {
            return PaymentQrParseResult.Failure(PaymentQrError.InvalidChecksum)
        }
        if (!isNqr(qrData)) {
            return PaymentQrParseResult.Failure(PaymentQrError.InvalidFormat)
        }

        val tags = EmvQr.extractTags(qrData, setOf(TAG_AMOUNT, TAG_MERCHANT_NAME))
        val merchantName =
            tags[TAG_MERCHANT_NAME]
                ?: return PaymentQrParseResult.Failure(PaymentQrError.MissingPaymentAddress)

        val amountStr = tags[TAG_AMOUNT]
        val fiatAmount =
            if (amountStr != null) {
                parsePositiveFiatAmount(amountStr)
                    ?: return PaymentQrParseResult.Failure(PaymentQrError.InvalidAmount(amountStr))
            } else {
                null
            }
        return PaymentQrParseResult.Success(ParsedPaymentQr(merchantName, fiatAmount))
    }

    @Suppress("ReturnCount")
    private fun parseSpd(qrData: String): PaymentQrParseResult {
        val parts = qrData.split(SPD_DELIMITER).filter { it.isNotEmpty() }
        if (parts.size < MIN_SPD_PARTS || parts[0] != SPD_TAG) {
            return PaymentQrParseResult.Failure(PaymentQrError.InvalidFormat)
        }

        val fields = mutableMapOf<String, String>()
        for (part in parts.drop(SPD_FIELD_START)) {
            val colon = part.indexOf(':')
            if (colon == -1) continue
            fields[part.substring(0, colon).uppercase()] = part.substring(colon + 1)
        }

        val account =
            fields[SPD_KEY_ACCOUNT]
                ?: return PaymentQrParseResult.Failure(PaymentQrError.MissingPaymentAddress)

        val amountStr = fields[SPD_KEY_AMOUNT]
        val fiatAmount =
            if (amountStr != null) {
                parsePositiveFiatAmount(amountStr.replace(",", ""))
                    ?: return PaymentQrParseResult.Failure(PaymentQrError.InvalidAmount(amountStr))
            } else {
                null
            }
        return PaymentQrParseResult.Success(ParsedPaymentQr(account, fiatAmount))
    }

    private fun isNqr(qrData: String): Boolean =
        qrData.contains(NIBSS_AID) ||
            qrData.contains(NGN_CURRENCY_TAG) ||
            qrData.contains(NG_COUNTRY_TAG)

    private const val SPD_PREFIX = "SPD*"
    private const val SPD_DELIMITER = "*"
    private const val SPD_TAG = "SPD"
    private const val MIN_SPD_PARTS = 2
    private const val SPD_FIELD_START = 2
    private const val SPD_KEY_ACCOUNT = "ACC"
    private const val SPD_KEY_AMOUNT = "AM"
    private const val NIBSS_AID = "NG.COM.NIBSSPLC.QR"
    private const val NGN_CURRENCY_TAG = "5303566"
    private const val NG_COUNTRY_TAG = "5802NG"
    private const val TAG_AMOUNT = "54"
    private const val TAG_MERCHANT_NAME = "59"
}
