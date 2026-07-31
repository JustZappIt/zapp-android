// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.model

import cash.z.ecc.android.sdk.model.FiatCurrency
import org.json.JSONObject
import java.math.BigDecimal

/**
 * MIME types used as protocol markers in chat messages. The string values are part of the
 * on-wire format — changing them breaks compatibility with peers running older clients.
 */
object MimeTypes {
    const val IMAGE_PREFIX = "image/"
    const val VIDEO_PREFIX = "video/"
    const val IMAGE_JPEG = "image/jpeg"
    const val GIF = "image/gif"
    const val LOCATION = "application/location"
    const val WALLET_ADDRESS = "application/wallet-address"
    const val PAYMENT_REQUEST = "application/payment-request"
    const val ZEC_TRANSACTION = "application/zec-transaction"
}

const val MAX_PAYMENT_REQUEST_ZEC = 21_000_000.0

data class PaymentRequestFiatAmount(
    val amount: BigDecimal,
    val currency: FiatCurrency,
)

/**
 * The one [MimeTypes.PAYMENT_REQUEST] payload schema, shared by the in-chat request/split-bill
 * flow and the Request wizard's "Send in chat". The field set is wire format (iOS parses the
 * same payloads) — additive changes only.
 */
fun buildPaymentRequestJson(
    id: String,
    amount: BigDecimal,
    requesterAddress: String,
    memo: String?,
    debtorId: String? = null,
    debtorName: String? = null,
    splitCount: Int? = null,
    fiat: PaymentRequestFiatAmount? = null,
): String =
    JSONObject()
        .put("requesterAddress", requesterAddress)
        .put("id", id)
        .put("amount", amount)
        .put("token", "ZEC")
        .apply {
            debtorId?.let { put("debtorId", it) }
            debtorName?.let { put("debtorName", it) }
            splitCount?.let { put("splitCount", it) }
            fiat?.let {
                put("fiatAmount", it.amount)
                put("fiatCurrency", it.currency.code)
            }
            memo?.takeIf { it.isNotEmpty() }?.let { put("memo", it) }
        }.toString()
