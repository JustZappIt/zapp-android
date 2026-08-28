// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import io.ktor.http.Url

/**
 * Parses a DeUna (Banco Pichincha, Ecuador) payment QR. Byte-compatible with `@p2pdotme/sdk`
 * v1.2.21 (`qr-parsers/parsers/ecu.ts`). Unlike every other corridor this is not a QR payload but
 * a plain URL: `https://pagar.deuna.app/<slug>/merchant?id=<hash>`.
 *
 * The `id` parameter alone is the payment address, so the host must be checked before it is
 * trusted — any site can serve a link carrying an `id`, and accepting one would send the payer's
 * funds to an address the Ecuadorian rail cannot resolve. DeUna QRs never carry an amount.
 *
 * Parsing goes through ktor's [Url] rather than by hand so that host and query resolution match the
 * WHATWG parser the SDK gets from `new URL()`. Hand-rolling this diverged on payloads that matter:
 * `https://evil.com\@pagar.deuna.app/…` was read as DeUna when its real host is `evil.com`, and
 * `?id=a&id=b` took the *last* value where the SDK takes the first — so the payer was shown a
 * destination other than the one the merchant would resolve.
 */
object EcuQrParser {
    @Suppress("ReturnCount")
    fun parse(qrData: String): PaymentQrParseResult {
        if (qrData.isBlank()) return PaymentQrParseResult.Failure(PaymentQrError.EmptyQr)
        val trimmed = qrData.trim()

        // Kept ahead of ktor: it resolves a protocol-relative `//host/…` to http, which the SDK's
        // `new URL()` rejects outright.
        val schemeEnd = trimmed.indexOf(SCHEME_SEPARATOR)
        if (schemeEnd <= 0 || !SCHEME_REGEX.matches(trimmed.substring(0, schemeEnd))) {
            return PaymentQrParseResult.Failure(PaymentQrError.InvalidFormat)
        }

        // `Url("not a url")` yields host `localhost` rather than throwing, so the host check below —
        // not the runCatching — is what rejects a non-URL.
        val url =
            runCatching { Url(trimmed) }.getOrNull()
                ?: return PaymentQrParseResult.Failure(PaymentQrError.InvalidFormat)
        if (!url.host.equals(DEUNA_HOST, ignoreCase = true)) {
            return PaymentQrParseResult.Failure(PaymentQrError.InvalidFormat)
        }

        val merchantId = url.parameters[PARAM_MERCHANT_ID]
        return if (merchantId.isNullOrEmpty()) {
            PaymentQrParseResult.Failure(PaymentQrError.MissingPaymentAddress)
        } else {
            PaymentQrParseResult.Success(ParsedPaymentQr(merchantId, null))
        }
    }

    private val SCHEME_REGEX = Regex("^[A-Za-z][A-Za-z0-9+.-]*$")

    private const val SCHEME_SEPARATOR = "://"
    private const val DEUNA_HOST = "pagar.deuna.app"
    private const val PARAM_MERCHANT_ID = "id"
}
