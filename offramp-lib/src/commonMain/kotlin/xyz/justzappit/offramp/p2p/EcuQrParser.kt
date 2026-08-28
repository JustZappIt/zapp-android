// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

/**
 * Parses a DeUna (Banco Pichincha, Ecuador) payment QR. Byte-compatible with `@p2pdotme/sdk`
 * v1.2.21 (`qr-parsers/parsers/ecu.ts`). Unlike every other corridor this is not a QR payload but
 * a plain URL: `https://pagar.deuna.app/<slug>/merchant?id=<hash>`.
 *
 * The `id` parameter alone is the payment address, so the host must be checked before it is
 * trusted — any site can serve a link carrying an `id`, and accepting one would send the payer's
 * funds to an address the Ecuadorian rail cannot resolve. DeUna QRs never carry an amount.
 *
 * Taken apart by hand rather than with `java.net.URI` to keep this file in `commonMain`.
 */
object EcuQrParser {
    @Suppress("ReturnCount")
    fun parse(qrData: String): PaymentQrParseResult {
        if (qrData.isBlank()) return PaymentQrParseResult.Failure(PaymentQrError.EmptyQr)
        val trimmed = qrData.trim()

        val schemeEnd = trimmed.indexOf(SCHEME_SEPARATOR)
        if (schemeEnd <= 0 || !SCHEME_REGEX.matches(trimmed.substring(0, schemeEnd))) {
            return PaymentQrParseResult.Failure(PaymentQrError.InvalidFormat)
        }

        val afterScheme = trimmed.substring(schemeEnd + SCHEME_SEPARATOR.length)
        val authority = afterScheme.substring(0, afterScheme.indexOfFirstOrEnd("/?#"))
        if (hostOf(authority) != DEUNA_HOST) {
            return PaymentQrParseResult.Failure(PaymentQrError.InvalidFormat)
        }

        val query = afterScheme.substringAfter('?', "").substringBefore('#')
        val merchantId = UpiQrParser.queryParam(query, PARAM_MERCHANT_ID)
        return if (merchantId.isNullOrEmpty()) {
            PaymentQrParseResult.Failure(PaymentQrError.MissingPaymentAddress)
        } else {
            PaymentQrParseResult.Success(ParsedPaymentQr(merchantId, null))
        }
    }

    /** Drops any `user:pass@` prefix and `:port` suffix, as a real URL parser would. */
    private fun hostOf(authority: String): String =
        authority
            .substringAfterLast('@')
            .substringBefore(':')
            .lowercase()

    private fun String.indexOfFirstOrEnd(chars: String): Int =
        indexOfFirst { it in chars }.takeIf { it >= 0 } ?: length

    private val SCHEME_REGEX = Regex("^[A-Za-z][A-Za-z0-9+.-]*$")

    private const val SCHEME_SEPARATOR = "://"
    private const val DEUNA_HOST = "pagar.deuna.app"
    private const val PARAM_MERCHANT_ID = "id"
}
