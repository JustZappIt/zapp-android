// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

/**
 * Parses a Venezuelan Pago Móvil QR. Byte-compatible with `@p2pdotme/sdk` v1.2.4
 * (`qr-parsers/parsers/ven.ts`): the payload is a base64 blob followed by `?` and a suffix — not
 * EMVCo — so only the prefix before `?` is validated (base64 alphabet) and the *entire* raw string
 * (blob + `?` + suffix) becomes the [ParsedPaymentQr.paymentAddress] the merchant is paid at. No
 * fixed amount, no CRC. Because there is no EMVCo currency tag, [PaymentQrDetector] can't route this
 * corridor from a generic scan — VEN is only reached when the order currency is already VEN.
 */
object PagoMovilQrParser {
    @Suppress("ReturnCount")
    fun parse(qrData: String): PaymentQrParseResult {
        if (qrData.isBlank()) return PaymentQrParseResult.Failure(PaymentQrError.EmptyQr)
        val trimmed = qrData.trim()

        val queryIndex = trimmed.indexOf('?')
        if (queryIndex == -1) return PaymentQrParseResult.Failure(PaymentQrError.InvalidFormat)

        val payload = trimmed.substring(0, queryIndex)
        if (payload.isEmpty() || !BASE64_REGEX.matches(payload)) {
            return PaymentQrParseResult.Failure(PaymentQrError.InvalidFormat)
        }

        return PaymentQrParseResult.Success(ParsedPaymentQr(trimmed, null))
    }

    private val BASE64_REGEX = Regex("^[A-Za-z0-9+/=]+$")
}
