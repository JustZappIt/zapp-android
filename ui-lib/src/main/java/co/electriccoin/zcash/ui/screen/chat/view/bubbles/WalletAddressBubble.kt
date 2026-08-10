// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view.bubbles

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.AndroidQrCodeImageGenerator
import co.electriccoin.zcash.ui.design.util.JvmQrCodeGenerator
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.view.MessageStatusIndicator
import co.electriccoin.zcash.ui.screen.chat.view.formatMessageTime
import org.json.JSONException
import org.json.JSONObject
import org.koin.compose.koinInject

private const val QR_PX = 400

@Composable
internal fun WalletAddressBubble(
    message: ChatMessage,
    isFromMe: Boolean,
    onSendToAddress: ((String) -> Unit)? = null,
) {
    val c = ZappTheme.colors
    val address =
        remember(message.content) {
            try {
                JSONObject(message.content).optString("content", message.content)
            } catch (_: JSONException) {
                message.content
            }
        }
    val copyToClipboard = koinInject<CopyToClipboardUseCase>()
    val copyLabel = stringResource(R.string.chat_p2p_key_copy_content_description)

    val qrBitmap =
        remember(address) {
            val pixels = JvmQrCodeGenerator.generate(address, QR_PX)
            AndroidQrCodeImageGenerator
                .generate(
                    bitArray = pixels,
                    sizePixels = QR_PX,
                    background = Color.White.toArgb(),
                    foreground = Color.Black.toArgb(),
                ).asImageBitmap()
        }

    Column(
        modifier =
            Modifier
                .widthIn(min = 220.dp, max = 264.dp)
                .background(c.surface, RectangleShape)
                .border(1.dp, c.borderStrong, RectangleShape),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(c.accentSoft)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Icon(
                Icons.Default.AccountBalanceWallet,
                contentDescription = null,
                tint = c.accentText,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            BasicText(
                text =
                    if (isFromMe) {
                        stringResource(R.string.chat_bubble_wallet_address_shared)
                    } else {
                        stringResource(R.string.chat_bubble_wallet_address)
                    }.uppercase(),
                style = ZappTheme.typography.eyebrow.copy(color = c.accentText),
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.background(Color.White, RectangleShape)) {
                Image(
                    bitmap = qrBitmap,
                    contentDescription = stringResource(R.string.chat_bubble_wallet_qr_content_description),
                    modifier =
                        Modifier
                            .size(136.dp)
                            .padding(7.dp),
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(c.surfaceAlt)
                        .padding(start = 9.dp, top = 7.dp, end = 3.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = address,
                    style = ZappTheme.typography.mono.copy(color = c.text),
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                )
                Box(
                    modifier =
                        Modifier
                            .size(36.dp)
                            .clickable { copyToClipboard(address) }
                            .semantics {
                                role = Role.Button
                                contentDescription = copyLabel
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = null,
                        tint = c.accentText,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }

            if (!isFromMe && onSendToAddress != null) {
                val sendLabel = stringResource(R.string.chat_bubble_wallet_address_send)
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 44.dp)
                            .background(c.accent, RectangleShape)
                            .clickable { onSendToAddress(address) }
                            .semantics {
                                role = Role.Button
                                contentDescription = sendLabel
                            }.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = sendLabel,
                        style = ZappTheme.typography.button.copy(color = c.onAccent),
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.End),
            ) {
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
}
