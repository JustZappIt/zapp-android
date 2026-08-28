// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

/**
 * Sniffs which corridor a scanned payload belongs to, so [PaymentQrParser] can reject a QR from the
 * wrong country before it is encrypted to a merchant who cannot settle it.
 *
 * Only payloads carrying an EMVCo currency tag can be sniffed. Three corridors are unreachable here
 * by construction and must be identified by the order's own currency instead:
 * - **VEN** Pago Móvil is opaque base64, with no tags at all.
 * - **CUP** Transfermóvil is a comma-separated record, not EMVCo.
 * - **ECU** DeUna is a plain `https://` URL, and Ecuador transacts in USD (`840`) — claiming that
 *   tag would misroute every genuinely dollar-denominated QR to Ecuador.
 *
 * **BOB** is a half case: Bolivia accepts two shapes (see [BobQrParser]), and only its encrypted
 * bank envelope is opaque. Its EMVCo QR is sniffed here like any other.
 */
object PaymentQrDetector {
    @Suppress("ReturnCount")
    fun detect(qrData: String): CurrencyCode? {
        val trimmed = qrData.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith(UPI_SCHEME, ignoreCase = true)) return CurrencyCode.Inr

        val tags = EmvQr.extractTags(trimmed, setOf(PAYLOAD_FORMAT_TAG, TRANSACTION_CURRENCY_TAG))
        if (tags[PAYLOAD_FORMAT_TAG] != PAYLOAD_FORMAT_MPM) return null
        return when (tags[TRANSACTION_CURRENCY_TAG]) {
            ISO4217_IDR -> CurrencyCode.Idr
            ISO4217_BRL -> CurrencyCode.Brl
            ISO4217_ARS -> CurrencyCode.Ars
            ISO4217_NGN -> CurrencyCode.Ngn
            ISO4217_COP -> CurrencyCode.Cop
            ISO4217_BOB -> CurrencyCode.Bob
            ISO4217_PEN -> CurrencyCode.Pen
            ISO4217_PHP -> CurrencyCode.Php
            else -> null
        }
    }

    private const val UPI_SCHEME = "upi:"
    private const val PAYLOAD_FORMAT_TAG = "00"
    private const val TRANSACTION_CURRENCY_TAG = "53"
    private const val PAYLOAD_FORMAT_MPM = "01"
    private const val ISO4217_IDR = "360"
    private const val ISO4217_BRL = "986"
    private const val ISO4217_ARS = "032"
    private const val ISO4217_NGN = "566"
    private const val ISO4217_COP = "170"
    private const val ISO4217_BOB = "068"
    private const val ISO4217_PEN = "604"
    private const val ISO4217_PHP = "608"
}
