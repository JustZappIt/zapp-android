// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

/**
 * Parses a Colombian QR in either DIAN electronic-invoice or EMVCo form. Byte-compatible with
 * `@p2pdotme/sdk` v1.2.4 (`qr-parsers/parsers/cop.ts`).
 *
 * - **DIAN** (`key:value` text with `CUFE`/`NumFac`): tried first. Once a DIAN marker is present the
 *   result commits — a missing CUFE/NumFac is a hard failure, not a fall-through to EMV. Fields split
 *   on `[\n,;]+`, first-`:` per segment, first-wins. Address is `CUFE` (any case) else `NumFac`.
 * - **EMVCo** (Nequi, Bre-B/RBM): requires BOTH the CO country tag (`5802CO`) AND the COP currency
 *   tag (`5303170`). Merchant name is tag 59 (or `"MERCHANT_NOT_FOUND"`), and a present-but-bad
 *   amount (tag 54) is silently dropped the way PIX does — never an error. No CRC on either path.
 */
object CopQrParser {
    @Suppress("ReturnCount")
    fun parse(qrData: String): PaymentQrParseResult {
        if (qrData.isBlank()) return PaymentQrParseResult.Failure(PaymentQrError.EmptyQr)
        val trimmed = qrData.trim()

        parseDianInvoice(trimmed)?.let { return it }
        parseColombianEmv(trimmed)?.let { return it }
        return PaymentQrParseResult.Failure(PaymentQrError.InvalidFormat)
    }

    /** Null only when no DIAN marker is present; otherwise a committed success or failure. */
    private fun parseDianInvoice(qrData: String): PaymentQrParseResult? {
        val hasDianMarker =
            DIAN_KEYS.any { qrData.contains("$it:") || qrData.contains("$it :") }
        if (!hasDianMarker) return null

        val fields = mutableMapOf<String, String>()
        for (segment in qrData.split(DIAN_DELIMITER)) {
            val colon = segment.indexOf(':')
            if (colon == -1) continue
            val key = segment.substring(0, colon).trim()
            val value = segment.substring(colon + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty() && key !in fields) fields[key] = value
        }

        val paymentAddress =
            fields[KEY_CUFE_UPPER] ?: fields[KEY_CUFE_TITLE] ?: fields[KEY_CUFE_LOWER] ?: fields[KEY_NUM_FAC]
        return if (paymentAddress == null) {
            PaymentQrParseResult.Failure(PaymentQrError.MissingPaymentAddress)
        } else {
            PaymentQrParseResult.Success(ParsedPaymentQr(paymentAddress, null))
        }
    }

    /** Null unless both the CO country tag and the COP currency tag are present. */
    private fun parseColombianEmv(qrData: String): PaymentQrParseResult? {
        if (!qrData.contains(CO_COUNTRY_TAG) || !qrData.contains(COP_CURRENCY_TAG)) return null

        val tags = EmvQr.extractTags(qrData, setOf(TAG_AMOUNT, TAG_MERCHANT_NAME))
        val merchantName = tags[TAG_MERCHANT_NAME] ?: MERCHANT_NOT_FOUND
        val fiatAmount = tags[TAG_AMOUNT]?.let { parsePositiveFiatAmount(it) }
        return PaymentQrParseResult.Success(ParsedPaymentQr(merchantName, fiatAmount))
    }

    private val DIAN_KEYS = listOf("NumFac", "CUFE", "Cufe", "NitFac", "ValFac", "ValTolFac")
    private val DIAN_DELIMITER = Regex("[\\n,;]+")
    private const val KEY_CUFE_UPPER = "CUFE"
    private const val KEY_CUFE_TITLE = "Cufe"
    private const val KEY_CUFE_LOWER = "cufe"
    private const val KEY_NUM_FAC = "NumFac"
    private const val CO_COUNTRY_TAG = "5802CO"
    private const val COP_CURRENCY_TAG = "5303170"
    private const val TAG_AMOUNT = "54"
    private const val TAG_MERCHANT_NAME = "59"
    private const val MERCHANT_NOT_FOUND = "MERCHANT_NOT_FOUND"
}
