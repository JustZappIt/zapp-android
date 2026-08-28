// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import xyz.justzappit.evm.math.BigDecimal

/**
 * A scanned merchant payment QR, reduced to the two fields the offramp needs: the [paymentAddress]
 * the merchant is paid at (a UPI VPA, a PIX merchant name, or a QRIS merchant name) and the
 * optional [fiatAmount] the QR fixes. A null amount means an open/payer-defined QR. The raw scanned
 * payload — not this parsed form — is what gets encrypted to the merchant on-chain; this exists to
 * pre-validate the scan and to prefill the amount field.
 */
data class ParsedPaymentQr(
    val paymentAddress: String,
    val fiatAmount: BigDecimal?,
)

/** Why a scan was rejected. UI surfaces a per-case message; finer-grained than the SDK's codes. */
sealed class PaymentQrError {
    object EmptyQr : PaymentQrError()

    /** Structurally not the expected EMV/URI shape (e.g. PIX missing the payload-format tag). */
    object InvalidFormat : PaymentQrError()

    object MissingPaymentAddress : PaymentQrError()

    data class InvalidPaymentAddress(
        val raw: String
    ) : PaymentQrError()

    /** EMVCo CRC-16 checksum did not verify (PIX). */
    object InvalidChecksum : PaymentQrError()

    data class InvalidAmount(
        val raw: String
    ) : PaymentQrError()

    /** Dynamic PIX: no resolver configured, or the bank-endpoint fetch failed. */
    data class DynamicFetchFailed(
        val reason: String
    ) : PaymentQrError()

    data class UnsupportedCurrency(
        val code: String
    ) : PaymentQrError()
}

sealed class PaymentQrParseResult {
    data class Success(
        val parsed: ParsedPaymentQr
    ) : PaymentQrParseResult()

    data class Failure(
        val error: PaymentQrError
    ) : PaymentQrParseResult()
}

/**
 * Parses [qrData] for [currency], dispatching to the per-rail parser exactly as the SDK's
 * `parseQR` switches on currency (`qr-parsers/parse-qr.ts`) — the rail is taken from the order's
 * currency, never sniffed from the QR. `suspend` only because dynamic PIX must fetch the amount
 * from the issuing bank; UPI and QRIS resolve synchronously.
 */
object PaymentQrParser {
    suspend fun parse(
        currency: CurrencyCode,
        qrData: String,
        dynamicPixResolver: DynamicPixResolver? = null,
        orderId: String? = null,
    ): PaymentQrParseResult {
        if (qrData.encodeToByteArray().size > MAX_PAYMENT_QR_BYTES) {
            return PaymentQrParseResult.Failure(PaymentQrError.InvalidFormat)
        }
        val detected = PaymentQrDetector.detect(qrData)
        if (detected != null && detected != currency) {
            return PaymentQrParseResult.Failure(PaymentQrError.InvalidFormat)
        }
        return parseForRail(currency, qrData, dynamicPixResolver, orderId)
    }

    private suspend fun parseForRail(
        currency: CurrencyCode,
        qrData: String,
        dynamicPixResolver: DynamicPixResolver?,
        orderId: String?,
    ): PaymentQrParseResult =
        when (currency) {
            CurrencyCode.Inr -> UpiQrParser.parseQr(qrData).toPaymentQrResult()
            CurrencyCode.Idr -> QrisQrParser.parse(qrData)
            CurrencyCode.Brl -> PixQrParser.parse(qrData, dynamicPixResolver, orderId)
            CurrencyCode.Ars -> MercadoPagoQrParser.parse(qrData)
            CurrencyCode.Ven -> PagoMovilQrParser.parse(qrData)
            CurrencyCode.Ngn -> NgnQrParser.parse(qrData)
            CurrencyCode.Cop -> CopQrParser.parse(qrData)
            CurrencyCode.Bob -> BobQrParser.parse(qrData)
            CurrencyCode.Cup -> CupQrParser.parse(qrData)
            CurrencyCode.Ecu -> EcuQrParser.parse(qrData)
            CurrencyCode.Pen -> PenQrParser.parse(qrData)
            CurrencyCode.Php -> PhpQrParser.parse(qrData)
        }

    private const val MAX_PAYMENT_QR_BYTES = 16 * 1024

    private fun UpiQrParseResult.toPaymentQrResult(): PaymentQrParseResult =
        when (this) {
            is UpiQrParseResult.Success -> {
                PaymentQrParseResult.Success(
                    ParsedPaymentQr(parsed.paymentAddress, parsed.fiatAmount),
                )
            }

            is UpiQrParseResult.Failure -> {
                PaymentQrParseResult.Failure(
                    when (val e = error) {
                        UpiQrError.EmptyQr -> PaymentQrError.EmptyQr
                        UpiQrError.MissingPaymentAddress -> PaymentQrError.MissingPaymentAddress
                        is UpiQrError.InvalidUpiId -> PaymentQrError.InvalidPaymentAddress(e.raw)
                        is UpiQrError.InvalidAmount -> PaymentQrError.InvalidAmount(e.raw)
                        is UpiQrError.InvalidCurrency -> PaymentQrError.InvalidFormat
                    },
                )
            }
        }
}

/**
 * Parses an EMV tag-54 / dynamic-PIX amount string to a positive [BigDecimal], or null if absent,
 * unparseable, or non-positive. Mirrors `parseAmount` (`qr-parsers/utils/amount.ts`) rejecting
 * NaN/<=0; callers decide how to surface null for a present amount field.
 */
internal fun parsePositiveFiatAmount(raw: String): BigDecimal? =
    runCatching { BigDecimal(raw.trim()) }.getOrNull()?.takeIf { it.signum() > 0 }
