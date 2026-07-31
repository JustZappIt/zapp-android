// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue

/**
 * Modal scrim-backed confirm/cancel dialog. The confirm button uses [ZappTheme.colors.danger]
 * when [isDestructive] is true (default) — set to false for non-destructive prompts.
 */
@Composable
internal fun ConfirmDialog(
    title: StringResource,
    body: StringResource,
    confirmLabel: StringResource,
    cancelLabel: StringResource,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isDestructive: Boolean = true,
) {
    val c = ZappTheme.colors
    val confirmBg = if (isDestructive) c.danger else c.accent
    val confirmFg = if (isDestructive) c.bg else c.onAccent

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.overlay)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .background(c.surface, RectangleShape)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    ).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BasicText(
                text = title.getValue(),
                style = ZappTheme.typography.rowTitle.copy(color = c.text),
            )
            BasicText(
                text = body.getValue(),
                style = ZappTheme.typography.body.copy(color = c.textMuted),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ConfirmDialogButton(
                    text = cancelLabel.getValue(),
                    background = c.surfaceAlt,
                    textColor = c.text,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                ConfirmDialogButton(
                    text = confirmLabel.getValue(),
                    background = confirmBg,
                    textColor = confirmFg,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ConfirmDialogButton(
    text: String,
    background: Color,
    textColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .height(48.dp)
                .background(background, RectangleShape)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style =
                ZappTheme.typography.button.copy(
                    color = textColor,
                    fontWeight = FontWeight.Black,
                ),
        )
    }
}
