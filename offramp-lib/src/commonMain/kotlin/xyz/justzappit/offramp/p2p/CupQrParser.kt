// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

/**
 * Parses a Cuban Transfermóvil QR. Byte-compatible with `@p2pdotme/sdk` v1.2.21
 * (`qr-parsers/parsers/cup.ts`). Not EMVCo — the payload is a comma-separated record:
 *
 * ```
 * TRANSFERMOVIL_ETECSA,<operation>,<card>,<phone>[,<amount>]
 * ```
 *
 * The payment address is the compound `phone|card` pair, in that order, matching the shape CUP
 * uses for a stored payment ID. Both halves are normalised first, so `+53 5 123 4567` and
 * `51234567` yield one address rather than two orders that look unrelated.
 */
object CupQrParser {
    @Suppress("ReturnCount")
    fun parse(qrData: String): PaymentQrParseResult {
        if (qrData.isBlank()) return PaymentQrParseResult.Failure(PaymentQrError.EmptyQr)
        val parts = qrData.trim().split(',').map { it.trim() }

        if (!parts.first().equals(TRANSFERMOVIL_PREFIX, ignoreCase = true)) {
            return PaymentQrParseResult.Failure(PaymentQrError.InvalidFormat)
        }
        if (parts.size <= PHONE_INDEX) {
            return PaymentQrParseResult.Failure(PaymentQrError.MissingPaymentAddress)
        }

        val rawCard = parts[CARD_INDEX]
        val card =
            normaliseCard(rawCard)
                ?: return PaymentQrParseResult.Failure(PaymentQrError.InvalidPaymentAddress(rawCard))
        val rawPhone = parts[PHONE_INDEX]
        val phone =
            normalisePhone(rawPhone)
                ?: return PaymentQrParseResult.Failure(PaymentQrError.InvalidPaymentAddress(rawPhone))

        val amountStr = parts.getOrNull(AMOUNT_INDEX)?.takeIf { it.isNotEmpty() }
        val fiatAmount = amountStr?.let { parsePositiveFiatAmount(it) }
        return if (amountStr != null && fiatAmount == null) {
            PaymentQrParseResult.Failure(PaymentQrError.InvalidAmount(amountStr))
        } else {
            PaymentQrParseResult.Success(ParsedPaymentQr("$phone|$card", fiatAmount))
        }
    }

    private fun normaliseCard(card: String): String? =
        card.filterNot { it.isWhitespace() || it == '-' }.takeIf { CARD_REGEX.matches(it) }

    /** 8 national digits, with an optional `53` country prefix stripped rather than rejected. */
    private fun normalisePhone(phone: String): String? {
        val digits = phone.filter { it in '0'..'9' }
        return when {
            NATIONAL_PHONE_REGEX.matches(digits) -> digits
            PREFIXED_PHONE_REGEX.matches(digits) -> digits.substring(COUNTRY_PREFIX_LEN)
            else -> null
        }
    }

    private val CARD_REGEX = Regex("^\\d{16}$")
    private val NATIONAL_PHONE_REGEX = Regex("^\\d{8}$")
    private val PREFIXED_PHONE_REGEX = Regex("^53\\d{8}$")

    private const val TRANSFERMOVIL_PREFIX = "TRANSFERMOVIL_ETECSA"
    private const val CARD_INDEX = 2
    private const val PHONE_INDEX = 3
    private const val AMOUNT_INDEX = 4
    private const val COUNTRY_PREFIX_LEN = 2
}
