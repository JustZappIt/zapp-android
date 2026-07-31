// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.screen.chat.newconv.NewConversationPrimaryAction
import co.electriccoin.zcash.ui.screen.chat.newconv.NewConversationState

@Composable
internal fun BottomDock(state: NewConversationState) {
    val c = ZappTheme.colors
    val backLabel = stringResource(R.string.chat_new_conversation_back_content_description)
    val (label, description) =
        when (state.primaryAction) {
            is NewConversationPrimaryAction.StartChat -> {
                stringResource(R.string.chat_new_conversation_start_chat_label) to
                    stringResource(R.string.chat_new_conversation_start_chat_content_description)
            }

            is NewConversationPrimaryAction.ScanQr -> {
                stringResource(R.string.chat_new_conversation_scan_qr_label) to
                    stringResource(R.string.chat_new_conversation_scan_qr_content_description)
            }
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 18.dp)
                .padding(bottom = 8.dp)
                .background(c.surface)
                .border(BorderStroke(1.dp, c.border), RectangleShape),
    ) {
        Box(
            modifier =
                Modifier
                    .size(width = 72.dp, height = 52.dp)
                    .border(BorderStroke(1.dp, c.border), RectangleShape)
                    .clickable(onClick = state.onBack)
                    .semantics {
                        contentDescription = backLabel
                        role = Role.Button
                    },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                "←",
                style =
                    ZappTheme.typography.button.copy(
                        color = c.text,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                    ),
            )
        }

        val isCreating =
            (state.primaryAction as? NewConversationPrimaryAction.StartChat)?.isCreating == true
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .height(52.dp)
                    .background(c.accent, RectangleShape)
                    .clickable(enabled = !isCreating, onClick = state.primaryAction.onClick)
                    .semantics {
                        contentDescription = description
                        role = Role.Button
                    },
            contentAlignment = Alignment.Center,
        ) {
            if (isCreating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = c.onAccent,
                    strokeWidth = 2.dp,
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.primaryAction is NewConversationPrimaryAction.ScanQr) {
                        Icon(
                            Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = c.onAccent,
                        )
                    }
                    BasicText(
                        text = label,
                        style =
                            ZappTheme.typography.button.copy(
                                color = c.onAccent,
                                fontWeight = FontWeight.Black,
                                letterSpacing = BUTTON_LETTER_SPACING,
                            ),
                    )
                }
            }
        }
    }
}

private val BUTTON_LETTER_SPACING = 0.6.sp
