// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

/**
 * Parses a Bolivian QR Simple payload. Byte-compatible with `@p2pdotme/sdk` v1.2.21 — with
 * `parseBolivia` (`qr-parsers/parsers/bob.ts`) *composed with* `validateBolivianQr`, which the
 * SDK's `parseQR` re-applies afterwards. Bolivia accepts two unrelated shapes:
 *
 * - **Encrypted envelope** `<base64 ciphertext>|<hex checksum>` (Yape Bs, BancoSol, Banco Fie).
 *   The bank encrypts account and amount, so only the envelope shape can be checked and the blob
 *   is re-rendered verbatim. The digest length varies by bank: 24 hex for Banco Fie, 32 otherwise.
 * - **EMVCo static QR**, country `BO` / currency `068`, CRC required by `validateBolivianQr`.
 *
 * A packed `qr||fields` payment ID is rejected: that is a stored SELL identifier, and treating one
 * as a QR would encrypt the wrong bytes to the merchant.
 */
object BobQrParser {
    @Suppress("ReturnCount")
    fun parse(qrData: String): PaymentQrParseResult {
        if (qrData.isBlank()) return PaymentQrParseResult.Failure(PaymentQrError.EmptyQr)
        val trimmed = qrData.trim()

        if (isEncryptedEnvelope(trimmed)) {
            return PaymentQrParseResult.Success(ParsedPaymentQr(trimmed, null))
        }

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

    private fun isEncryptedEnvelope(payload: String): Boolean {
        val separator = payload.lastIndexOf('|')
        if (separator < MIN_BLOB_LEN || payload.contains(PACKED_PAYMENT_ID_SEP)) return false
        val blob = payload.substring(0, separator)
        return blob.length % BASE64_BLOCK == 0 &&
            BASE64_REGEX.matches(blob) &&
            DIGEST_REGEX.matches(payload.substring(separator + 1))
    }

    private val BASE64_REGEX = Regex("^[A-Za-z0-9+/]+={0,2}$")
    private val DIGEST_REGEX = Regex("^(?:[0-9A-Fa-f]{24}|[0-9A-Fa-f]{32})$")

    /** The SDK's `PACKED_PAYMENT_ID_SEP`. */
    private const val PACKED_PAYMENT_ID_SEP = "||"
    private const val MIN_BLOB_LEN = 40
    private const val BASE64_BLOCK = 4
    private const val TAG_AMOUNT = "54"
    private const val TAG_CURRENCY = "53"
    private const val TAG_COUNTRY = "58"
    private const val EXPECTED_CURRENCY = "068"
    private const val EXPECTED_COUNTRY = "BO"
}
