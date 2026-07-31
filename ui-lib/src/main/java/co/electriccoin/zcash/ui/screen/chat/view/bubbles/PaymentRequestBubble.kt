// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view.bubbles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.wallet.ZecFiatRate
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.MAX_PAYMENT_REQUEST_ZEC
import co.electriccoin.zcash.ui.screen.chat.view.formatMessageTime
import co.electriccoin.zcash.ui.screen.chat.view.formatZecAmount
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
internal fun PaymentRequestBubble(
    message: ChatMessage,
    isFromMe: Boolean,
    localPublicKey: String?,
    fiatRate: ZecFiatRate?,
    isPaid: Boolean,
    onPay: (ChatMessage) -> Unit,
) {
    val c = ZappTheme.colors
    val data =
        remember(message.content, fiatRate) {
            parsePaymentRequest(message.content, fiatRate)
        }
    // The recipient owes when the request targets them (or targets no one, in a 1:1).
    val isMineToPay = !isFromMe && (data.debtorId == null || data.debtorId == localPublicKey)
    // Tapping anywhere on an unpaid, owed request prefills the send form for approval.
    val isPayable = isMineToPay && !isPaid && data.isAmountValid

    Column(
        modifier =
            Modifier
                .widthIn(min = 232.dp, max = 264.dp)
                .background(c.surface, RectangleShape)
                .border(width = 1.dp, color = c.borderStrong, shape = RectangleShape)
                .then(
                    if (isPayable) {
                        // Merge the amount/memo/time into one button node so TalkBack reads them
                        // instead of only announcing a bare "Pay".
                        Modifier
                            .clickable { onPay(message) }
                            .semantics(mergeDescendants = true) { role = Role.Button }
                    } else {
                        Modifier
                    },
                ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(c.accentSoft)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Payments,
                contentDescription = null,
                tint = c.accentText,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            BasicText(
                text = paymentRequestHeadline(isFromMe, isMineToPay, data.debtorName).uppercase(),
                style = ZappTheme.typography.eyebrow.copy(color = c.accentText),
            )
        }
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            BasicText(
                text = data.amountLabel,
                style = ZappTheme.typography.screenTitle.copy(color = c.text),
            )
            data.zecEquivalentLabel?.let {
                Spacer(modifier = Modifier.height(3.dp))
                BasicText(text = it, style = ZappTheme.typography.body.copy(color = c.textMuted))
            }
            if (data.splitCount > 1) {
                Spacer(modifier = Modifier.height(7.dp))
                BasicText(
                    text = stringResource(R.string.chat_bubble_payment_request_split_fmt, data.splitCount.toString()),
                    style = ZappTheme.typography.chip.copy(color = c.accentText),
                    modifier = Modifier.background(c.accentSoft).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            data.memo?.let {
                Spacer(modifier = Modifier.height(8.dp))
                BasicText(
                    text = it,
                    style = ZappTheme.typography.body.copy(color = c.textMuted),
                    modifier = Modifier.fillMaxWidth().background(c.surfaceAlt).padding(8.dp),
                )
            }
        }
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            BasicText(
                text = formatMessageTime(message.timestamp),
                style = ZappTheme.typography.caption.copy(color = c.textSubtle),
                modifier = Modifier.align(Alignment.End),
            )
            PaymentRequestAction(isPaid = isPaid, showPayAction = isPayable, message = message, onPay = onPay)
        }
    }
}

@Composable
private fun PaymentRequestAction(
    isPaid: Boolean,
    showPayAction: Boolean,
    message: ChatMessage,
    onPay: (ChatMessage) -> Unit,
) {
    val c = ZappTheme.colors
    when {
        isPaid -> {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = c.success,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                BasicText(
                    text = stringResource(R.string.chat_bubble_payment_request_paid),
                    style = ZappTheme.typography.chip.copy(color = c.success),
                )
            }
        }

        showPayAction -> {
            Spacer(modifier = Modifier.height(10.dp))
            ZappButton(
                text = stringResource(R.string.chat_bubble_payment_request_pay),
                modifier = Modifier.fillMaxWidth(),
                onClick = { onPay(message) },
            )
        }
    }
}

@Composable
private fun paymentRequestHeadline(isFromMe: Boolean, isMineToPay: Boolean, debtorName: String?): String =
    when {
        isFromMe -> {
            stringResource(R.string.chat_bubble_payment_request_sent)
        }

        isMineToPay -> {
            stringResource(R.string.chat_bubble_payment_request_owe)
        }

        else -> {
            stringResource(
                R.string.chat_bubble_payment_request_owes_fmt,
                debtorName ?: stringResource(R.string.chat_room_reply_unknown_sender),
            )
        }
    }

private data class ParsedPaymentRequest(
    val amountLabel: String,
    val zecEquivalentLabel: String?,
    val isAmountValid: Boolean,
    val memo: String?,
    val debtorId: String?,
    val debtorName: String?,
    val splitCount: Int,
)

private fun parsePaymentRequest(
    content: String,
    liveRate: ZecFiatRate?,
): ParsedPaymentRequest {
    val parsed = runCatching { JSONObject(content) }.getOrNull()
    val amount = paymentRequestAmount(parsed)
    val token = paymentRequestToken(parsed)
    val zecLabel = "${formatZecAmount(amount)} $token"
    val (embeddedFiat, liveFiat) = fiatLabels(parsed, amount, liveRate)
    return ParsedPaymentRequest(
        amountLabel = embeddedFiat ?: liveFiat ?: zecLabel,
        zecEquivalentLabel =
            when {
                embeddedFiat != null -> "≈ $zecLabel"
                liveFiat != null -> zecLabel
                else -> null
            },
        isAmountValid = amount > 0.0 && amount <= MAX_PAYMENT_REQUEST_ZEC,
        memo = parsed?.optString("memo", "")?.takeIf { it.isNotEmpty() },
        debtorId = parsed?.optString("debtorId", "")?.takeIf { it.isNotEmpty() },
        debtorName = parsed?.optString("debtorName", "")?.takeIf { it.isNotEmpty() },
        splitCount = parsed?.optInt("splitCount", 0) ?: 0,
    )
}

private fun paymentRequestAmount(parsed: JSONObject?): Double =
    parsed?.optDouble("amount", 0.0)?.takeIf(Double::isFinite) ?: 0.0

private fun paymentRequestToken(parsed: JSONObject?): String =
    parsed?.optString("token", "ZEC")?.takeIf(String::isNotEmpty) ?: "ZEC"

private fun fiatLabels(
    parsed: JSONObject?,
    zecAmount: Double,
    liveRate: ZecFiatRate?,
): Pair<String?, String?> {
    if (liveRate == null) return null to null
    val embedded =
        fiatLabelOrNull(
            amount = parsed?.optDouble("fiatAmount", 0.0) ?: 0.0,
            code = parsed?.optString("fiatCurrency", "")?.takeIf(String::isNotEmpty),
        )
    return embedded to liveFiatLabel(embedded, zecAmount, liveRate)
}

private fun liveFiatLabel(embedded: String?, zecAmount: Double, rate: ZecFiatRate): String? {
    if (embedded != null || zecAmount <= 0.0) return null
    val converted =
        rate
            .zecToFiat(BigDecimal.valueOf(zecAmount))
            .setScale(FIAT_DECIMALS, RoundingMode.HALF_UP)
            .toPlainString()
    return "≈ ${rate.symbol}$converted"
}

private fun fiatLabelOrNull(amount: Double, code: String?): String? =
    code
        ?.takeIf(FiatCurrency::isAlpha3Code)
        ?.takeIf { amount.isFinite() && amount > 0.0 }
        ?.let { currencyCode ->
            val symbol = runCatching { FiatCurrency(currencyCode).symbol }.getOrNull() ?: currencyCode
            val formatted =
                BigDecimal
                    .valueOf(amount)
                    .setScale(FIAT_DECIMALS, RoundingMode.HALF_UP)
                    .toPlainString()
            "$symbol$formatted"
        }

private const val FIAT_DECIMALS = 2
