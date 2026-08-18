// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.LocalKeyboardManager
import co.electriccoin.zcash.ui.design.animation.ZappMotion
import co.electriccoin.zcash.ui.design.animation.pressScale
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.screen.chat.room.ChatRoomInputState
import kotlinx.coroutines.launch

private val INPUT_CONTROL_SIZE = 50.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InputRow(state: ChatRoomInputState) {
    val c = ZappTheme.colors
    val focusManager = LocalFocusManager.current
    val keyboardManager = LocalKeyboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val attachContentDescription = state.attachContentDescription.getValue()
    val sendContentDescription = state.sendContentDescription.getValue()
    val textFieldState = rememberTextFieldState(initialText = state.value)
    val onChange by rememberUpdatedState(state.onChange)
    val receiveContentListener =
        state.onMediaCommitted?.let { onMediaCommitted ->
            remember(onMediaCommitted) {
                ReceiveContentListener { content ->
                    if (content.hasMediaType(MediaType.Image)) {
                        content.consume { item ->
                            item.uri?.let {
                                onMediaCommitted(it)
                                true
                            } ?: false
                        }
                    } else {
                        content
                    }
                }
            }
        }

    LaunchedEffect(state.value) {
        if (textFieldState.text.toString() != state.value) {
            textFieldState.setTextAndPlaceCursorAtEnd(state.value)
        }
    }
    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }.collect(onChange)
    }

    Column(modifier = Modifier.fillMaxWidth().background(c.surface)) {
        state.replyPreview?.let { reply ->
            ReplyPreviewStrip(
                senderName = reply.senderName,
                content = reply.content,
                onDismiss = reply.onDismiss,
            )
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            // Bottom-aligned so the buttons stay anchored beside the last line
            // when the field grows to multiple lines.
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(INPUT_CONTROL_SIZE)
                        .background(c.surfaceAlt, RectangleShape)
                        .border(BorderStroke(1.dp, c.border), RectangleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = c.accent),
                            onClick = {
                                // A focused field can reopen the IME after a modal is dismissed.
                                // Wait for the IME to close so it never competes with the sheet.
                                focusManager.clearFocus(force = true)
                                coroutineScope.launch {
                                    keyboardManager.close()
                                    state.onAttachClick()
                                }
                            },
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = attachContentDescription,
                    tint = c.accent,
                    modifier = Modifier.size(24.dp),
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            TextField(
                state = textFieldState,
                modifier =
                    Modifier
                        .weight(1f)
                        // Beats the 56dp TextFieldDefaults.MinHeight that defaultMinSize would
                        // otherwise apply, so the field matches the buttons beside it.
                        .heightIn(min = INPUT_CONTROL_SIZE)
                        .then(
                            if (receiveContentListener != null) {
                                Modifier.contentReceiver(receiveContentListener)
                            } else {
                                Modifier
                            }
                        ),
                textStyle = ZappTheme.typography.body,
                placeholder = {
                    BasicText(
                        text = state.placeholder.getValue(),
                        style = ZappTheme.typography.body.copy(color = c.textSubtle),
                    )
                },
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
                shape = RectangleShape,
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = c.surfaceInput,
                        unfocusedContainerColor = c.surfaceInput,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = c.text,
                        unfocusedTextColor = c.text,
                        cursorColor = c.accent,
                    ),
            )

            Spacer(modifier = Modifier.width(8.dp))

            val sendBg by
                animateColorAsState(
                    targetValue = if (state.canSend) c.accent else c.surfaceAlt,
                    animationSpec = tween(ZappMotion.CONTENT_MS, easing = ZappMotion.easing),
                    label = "sendBg",
                )
            val sendTint by
                animateColorAsState(
                    targetValue = if (state.canSend) c.onAccent else c.textSubtle,
                    animationSpec = tween(ZappMotion.CONTENT_MS, easing = ZappMotion.easing),
                    label = "sendTint",
                )
            val sendInteractionSource = remember { MutableInteractionSource() }
            val haptic = LocalHapticFeedback.current
            Box(
                modifier =
                    Modifier
                        .size(INPUT_CONTROL_SIZE)
                        .pressScale(sendInteractionSource)
                        .background(sendBg, RectangleShape)
                        .border(BorderStroke(1.dp, c.border), RectangleShape)
                        .clickable(
                            enabled = state.canSend,
                            interactionSource = sendInteractionSource,
                            indication = ripple(color = c.onAccent),
                            onClick = {
                                runCatching { haptic.performHapticFeedback(HapticFeedbackType.Confirm) }
                                state.onSendClick()
                            },
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.ArrowUpward,
                    contentDescription = sendContentDescription,
                    tint = sendTint,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun ReplyPreviewStrip(
    senderName: String,
    content: String,
    onDismiss: () -> Unit,
) {
    val c = ZappTheme.colors

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(c.border),
    )

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(c.surfaceAlt)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .width(3.dp)
                    .height(32.dp)
                    .background(c.accent),
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
        ) {
            BasicText(
                text = stringResource(R.string.chat_room_reply_to_fmt, senderName),
                style = ZappTheme.typography.chip.copy(color = c.accent),
                maxLines = 1,
            )
            BasicText(
                text = content,
                style = ZappTheme.typography.caption.copy(color = c.textMuted),
                maxLines = 1,
            )
        }
        Box(
            modifier =
                Modifier
                    .size(24.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = false, radius = 16.dp, color = c.accent),
                        onClick = onDismiss,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.chat_room_reply_dismiss),
                tint = c.textSubtle,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
