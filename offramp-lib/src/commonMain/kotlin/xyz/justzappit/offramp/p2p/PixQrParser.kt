// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import kotlinx.coroutines.CancellationException

/**
 * Parses a Brazilian PIX "copia e cola" (EMVCo MPM) merchant QR. Byte-compatible with
 * `@p2pdotme/sdk` v1.1.7 (`qr-parsers/parsers/brl.ts`): verify the CRC, require the payload-format
 * tag (00), take the merchant name (tag 59, or "MERCHANT_NOT_FOUND"), and resolve the amount from
 * the static tag 54 — or, for a dynamic QR whose tag 26 embeds a bank `location` URL, by fetching
 * it through [DynamicPixResolver]. A present-but-unparseable amount is rejected instead of being
 * treated as payer-defined; otherwise Android could encrypt a payload whose amount disagrees with
 * the amount sent to the contract.
 */
object PixQrParser {
    @Suppress("ReturnCount")
    suspend fun parse(
        qrData: String,
        resolver: DynamicPixResolver?,
        orderId: String? = null,
    ): PaymentQrParseResult {
        if (qrData.isBlank()) return PaymentQrParseResult.Failure(PaymentQrError.EmptyQr)
        val trimmed = qrData.trim()

        if (!EmvQr.verifyCrc16(trimmed)) {
            return PaymentQrParseResult.Failure(PaymentQrError.InvalidChecksum)
        }

        val tags = mutableMapOf<String, String>()
        for (entry in EmvQr.parseTlv(trimmed)) tags[entry.tag] = entry.value

        if (tags[TAG_PAYLOAD_FORMAT] == null) {
            return PaymentQrParseResult.Failure(PaymentQrError.InvalidFormat)
        }
        if (tags[TAG_CURRENCY] != EXPECTED_CURRENCY ||
            tags[TAG_COUNTRY]?.let { it != EXPECTED_COUNTRY } == true
        ) {
            return PaymentQrParseResult.Failure(PaymentQrError.InvalidFormat)
        }

        val merchantName = tags[TAG_MERCHANT_NAME] ?: MERCHANT_NOT_FOUND

        val location = tags[TAG_PIX_KEY_INFO]?.let { extractLocation(it) }
        val dynamicAmount: String? =
            if (location != null) {
                fetchDynamicAmount(location, resolver, orderId).getOrElse { failure ->
                    return PaymentQrParseResult.Failure(
                        PaymentQrError.DynamicFetchFailed(failure.message ?: "dynamic PIX fetch failed"),
                    )
                }
            } else {
                null
            }

        val amountStr = dynamicAmount ?: tags[TAG_AMOUNT]
        val fiatAmount =
            if (amountStr != null) {
                parsePositiveFiatAmount(amountStr)
                    ?: return PaymentQrParseResult.Failure(PaymentQrError.InvalidAmount(amountStr))
            } else {
                null
            }

        return PaymentQrParseResult.Success(ParsedPaymentQr(merchantName, fiatAmount))
    }

    /**
     * Resolves the dynamic-QR amount through [resolver]. The resolver-missing case and any fetch
     * failure surface as a [Result.failure] whose message feeds [PaymentQrError.DynamicFetchFailed].
     */
    private suspend fun fetchDynamicAmount(
        location: String,
        resolver: DynamicPixResolver?,
        orderId: String?,
    ): Result<String?> {
        if (resolver == null) {
            return Result.failure(IllegalStateException("resolver required for dynamic PIX QR codes"))
        }
        return try {
            Result.success(resolver.resolveAmount(location, orderId))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Exception
        ) {
            Result.failure(failure)
        }
    }

    /**
     * Pulls the dynamic-PIX `location` URL out of the tag-26 PIX-key-info template: subtag 25, or
     * subtag 01 when it looks like a URL. Last-wins and the `https://` default mirror
     * `parsePIXKeyInfo`.
     */
    private fun extractLocation(pixKeyInfo: String): String? {
        var location: String? = null
        for (entry in EmvQr.parseTlv(pixKeyInfo)) {
            when (entry.tag) {
                SUBTAG_KEY_OR_URL -> {
                    if (entry.value.contains("http") || entry.value.contains("://")) {
                        location = normalizeUrl(entry.value)
                    }
                }

                SUBTAG_LOCATION -> {
                    location = normalizeUrl(entry.value)
                }
            }
        }
        return location
    }

    private fun normalizeUrl(value: String): String = if (value.startsWith("http")) value else "https://$value"

    private const val TAG_PAYLOAD_FORMAT = "00"
    private const val TAG_PIX_KEY_INFO = "26"
    private const val TAG_AMOUNT = "54"
    private const val TAG_MERCHANT_NAME = "59"
    private const val TAG_CURRENCY = "53"
    private const val TAG_COUNTRY = "58"
    private const val EXPECTED_CURRENCY = "986"
    private const val EXPECTED_COUNTRY = "BR"
    private const val SUBTAG_KEY_OR_URL = "01"
    private const val SUBTAG_LOCATION = "25"
    private const val MERCHANT_NOT_FOUND = "MERCHANT_NOT_FOUND"
}
