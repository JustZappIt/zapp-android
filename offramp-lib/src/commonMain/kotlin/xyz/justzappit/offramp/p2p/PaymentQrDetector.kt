// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

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
}
