// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view.bubbles

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
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
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.view.MessageStatusIndicator
import co.electriccoin.zcash.ui.screen.chat.view.formatMessageTime
import co.electriccoin.zcash.ui.screen.chat.view.formatZecAmount
import org.json.JSONException
import org.json.JSONObject

private data class ParsedTransaction(
    val amount: Double,
    val signature: String?,
    val txId: String?,
)

@Composable
internal fun TransactionBubble(
    message: ChatMessage,
    isFromMe: Boolean,
    onViewTransaction: ((String) -> Unit)? = null,
) {
    val c = ZappTheme.colors
    val data =
        remember(message.content) {
            val parsed =
                try {
                    JSONObject(message.content)
                } catch (_: JSONException) {
                    null
                }
            ParsedTransaction(
                amount = parsed?.optDouble("amount", 0.0) ?: 0.0,
                signature = parsed?.optString("signature", "")?.takeIf { it.isNotEmpty() },
                txId = parsed?.optString("txId", "")?.takeIf { it.isNotEmpty() },
            )
        }

    val txId = data.txId
    Column(
        modifier =
            Modifier
                .widthIn(max = 280.dp)
                .background(c.accent.copy(alpha = 0.1f), RectangleShape)
                .then(
                    if (txId != null && onViewTransaction != null) {
                        Modifier
                            .clickable { onViewTransaction(txId) }
                            .semantics(mergeDescendants = true) { role = Role.Button }
                    } else {
                        Modifier
                    }
                ).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = c.accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            BasicText(
                text =
                    if (isFromMe) {
                        stringResource(R.string.chat_bubble_transaction_sent)
                    } else {
                        stringResource(R.string.chat_bubble_transaction_received)
                    },
                style = ZappTheme.typography.caption.copy(color = c.accent),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        BasicText(
            text = "${formatZecAmount(data.amount)} ZEC",
            style = ZappTheme.typography.rowTitle.copy(color = c.text),
        )
        data.signature?.let {
            Spacer(modifier = Modifier.height(4.dp))
            BasicText(
                text = stringResource(R.string.chat_bubble_transaction_signature_fmt, it.take(8), it.takeLast(4)),
                style = ZappTheme.typography.caption.copy(color = c.textMuted),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                text = formatMessageTime(message.timestamp),
                style = ZappTheme.typography.caption.copy(color = c.textMuted),
            )
            if (isFromMe) {
                Spacer(modifier = Modifier.width(4.dp))
                MessageStatusIndicator(
                    status = message.status,
                    mutedColor = c.textMuted,
                    readColor = c.accent,
                )
            }
        }
    }
}
