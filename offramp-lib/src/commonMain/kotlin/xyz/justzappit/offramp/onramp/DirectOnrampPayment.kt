// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.UpiPayUri
import xyz.justzappit.offramp.p2p.Usdc6

/** Decodes p2p.me's stored payment ID into payable QR and field instructions. */
internal fun paymentInstructionFor(
    payTo: String,
    orderId: BigInteger,
    fiat: Usdc6,
    currency: CurrencyCode?,
): OnrampPaymentInstruction =
    when (currency) {
        null -> {
            OnrampPaymentInstruction.Plain(payTo)
        }

        CurrencyCode.Inr -> {
            OnrampPaymentInstruction.Upi(
                address = payTo,
                intentUrl = UpiPayUri.buildBuyIntent(payTo, orderId, fiat, currency.code),
                amount = UpiPayUri.twoDecimalAmount(fiat),
            )
        }

        else -> {
            paymentDetails(payTo, currency).toInstruction(payTo)
        }
    }

private data class PaymentDetails(
    val qrPayload: String?,
    val fields: List<OnrampPaymentInstruction.Field>,
) {
    fun toInstruction(fallback: String): OnrampPaymentInstruction =
        when {
            qrPayload != null && fields.isNotEmpty() -> {
                OnrampPaymentInstruction.Fields(fields, qrPayload)
            }

            qrPayload != null -> {
                OnrampPaymentInstruction.Qr(qrPayload)
            }

            fields.isNotEmpty() -> {
                OnrampPaymentInstruction.Fields(fields)
            }

            else -> {
                OnrampPaymentInstruction.Plain(fallback)
            }
        }
}

private fun paymentDetails(paymentId: String, currency: CurrencyCode): PaymentDetails {
    val trimmed = paymentId.trim()
    val packedAt = trimmed.indexOf(PACKED_PAYMENT_ID_SEPARATOR)
    val qrCandidate = if (packedAt >= 0) trimmed.substring(0, packedAt).trim() else trimmed
    val qrPayload = qrCandidate.takeIf { currency.isPayQr(it) }
    val typed =
        when {
            packedAt >= 0 -> trimmed.substring(packedAt + PACKED_PAYMENT_ID_SEPARATOR.length).trim()
            qrPayload != null -> ""
            else -> trimmed
        }
    return PaymentDetails(qrPayload, currency.fieldsFrom(typed))
}

private fun CurrencyCode.fieldsFrom(value: String): List<OnrampPaymentInstruction.Field> =
    when {
        value.isBlank() -> {
            emptyList()
        }

        this == CurrencyCode.Pen -> {
            peruvianFields(value)
        }

        else -> {
            val values = value.split(FIELD_SEPARATOR)
            fieldKinds.mapIndexedNotNull { index, kind ->
                values
                    .getOrNull(index)
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let { field(kind, it) }
            }
        }
    }

private fun peruvianFields(value: String): List<OnrampPaymentInstruction.Field> =
    value
        .split(FIELD_SEPARATOR)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapNotNull { token ->
            val phone = token.normalisedPeruvianPhone()
            val digits = token.filter(Char::isDigit)
            when {
                phone != null -> {
                    field(OnrampPaymentFieldKind.PHONE_NUMBER, phone)
                }

                digits.length == PERUVIAN_CCI_DIGITS -> {
                    field(OnrampPaymentFieldKind.CCI, digits)
                }

                else -> {
                    null
                }
            }
        }

private fun String.normalisedPeruvianPhone(): String? {
    val digits = filter(Char::isDigit).removePrefix(PERU_COUNTRY_CODE)
    return digits.takeIf { it.length == PERUVIAN_PHONE_DIGITS && it.startsWith(PERUVIAN_PHONE_PREFIX) }
}

private fun field(kind: OnrampPaymentFieldKind, value: String) =
    OnrampPaymentInstruction.Field(label = null, value = value, kind = kind)

private val CurrencyCode.fieldKinds: List<OnrampPaymentFieldKind>
    get() = PAYMENT_FIELD_KINDS[this].orEmpty()

private fun CurrencyCode.isPayQr(payload: String): Boolean =
    when (this) {
        CurrencyCode.Ven -> payload.isVenezuelanPayQr()
        CurrencyCode.Pen -> payload.isEmvPayQr(country = "PE", currency = "604")
        CurrencyCode.Php -> payload.isEmvPayQr(country = "PH", currency = "608")
        CurrencyCode.Bob -> payload.isEmvPayQr(country = "BO", currency = "068") || payload.isBolivianEnvelope()
        else -> false
    }

private fun String.isVenezuelanPayQr(): Boolean {
    val queryAt = indexOf('?')
    if (queryAt <= 0) return false
    return VENEZUELAN_BLOB.matches(substring(0, queryAt))
}

private fun String.isEmvPayQr(country: String, currency: String): Boolean =
    startsWith("0002") && contains("5802$country") && contains("5303$currency")

private fun String.isBolivianEnvelope(): Boolean {
    val separator = lastIndexOf(FIELD_SEPARATOR)
    if (separator < BOLIVIAN_MINIMUM_BLOB_LENGTH) return false
    val blob = substring(0, separator)
    val tag = substring(separator + 1)
    return blob.length % BASE64_BLOCK_LENGTH == 0 &&
        BOLIVIAN_BLOB.matches(blob) &&
        BOLIVIAN_TAG.matches(tag)
}

private val PAYMENT_FIELD_KINDS =
    mapOf(
        CurrencyCode.Brl to listOf(OnrampPaymentFieldKind.PIX_KEY),
        CurrencyCode.Idr to listOf(OnrampPaymentFieldKind.PHONE_NUMBER),
        CurrencyCode.Ars to listOf(OnrampPaymentFieldKind.PAYMENT_ALIAS),
        CurrencyCode.Cop to listOf(OnrampPaymentFieldKind.PAYMENT_ALIAS),
        CurrencyCode.Ven to
            listOf(
                OnrampPaymentFieldKind.PHONE_NUMBER,
                OnrampPaymentFieldKind.DOCUMENT_ID,
                OnrampPaymentFieldKind.BANK,
            ),
        CurrencyCode.Ngn to
            listOf(
                OnrampPaymentFieldKind.ACCOUNT_NUMBER,
                OnrampPaymentFieldKind.BANK_NAME,
                OnrampPaymentFieldKind.ACCOUNT_NAME,
            ),
        CurrencyCode.Bob to listOf(OnrampPaymentFieldKind.ACCOUNT_NUMBER),
        CurrencyCode.Cup to
            listOf(
                OnrampPaymentFieldKind.PHONE_NUMBER,
                OnrampPaymentFieldKind.CARD_NUMBER,
            ),
        CurrencyCode.Ecu to
            listOf(
                OnrampPaymentFieldKind.BANK_NAME,
                OnrampPaymentFieldKind.ACCOUNT_TYPE,
                OnrampPaymentFieldKind.ACCOUNT_NUMBER,
                OnrampPaymentFieldKind.ACCOUNT_NAME,
                OnrampPaymentFieldKind.CEDULA,
            ),
        CurrencyCode.Php to
            listOf(
                OnrampPaymentFieldKind.PHONE_NUMBER,
                OnrampPaymentFieldKind.BANK_NAME,
            ),
    )

private const val PACKED_PAYMENT_ID_SEPARATOR = "||"
private const val FIELD_SEPARATOR = '|'
private const val PERU_COUNTRY_CODE = "51"
private const val PERUVIAN_PHONE_PREFIX = "9"
private const val PERUVIAN_PHONE_DIGITS = 9
private const val PERUVIAN_CCI_DIGITS = 20
private const val BOLIVIAN_MINIMUM_BLOB_LENGTH = 40
private const val BASE64_BLOCK_LENGTH = 4

private val VENEZUELAN_BLOB = Regex("^[A-Za-z0-9+/=]+$")
private val BOLIVIAN_BLOB = Regex("^[A-Za-z0-9+/]+={0,2}$")
private val BOLIVIAN_TAG = Regex("^[0-9A-Fa-f]{16,64}$")
