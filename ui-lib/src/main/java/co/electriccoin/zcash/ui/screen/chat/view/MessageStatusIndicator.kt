// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.animation.ZappMotion
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus

/**
 * Delivery state on an outgoing bubble, rendered as a typographic mark in the
 * Swiss style (no Material icons): a single tick once the blind relay accepts the
 * encrypted block, a second once the recipient confirms delivery, and the pair
 * highlighted once the peer has read it. Relay acceptance is weaker than delivery,
 * so the two must not collapse into one mark.
 *
 * Colours are supplied by the caller because outgoing bubbles come in two
 * families: solid-accent (pass an [onAccent][ZappTheme] pair) and light-tinted
 * cards (pass a muted/accent pair). [readColor] is the emphasised colour for the
 * read pair; [mutedColor] carries the resting sent/delivered/sending states.
 */
@Composable
internal fun MessageStatusIndicator(
    status: MessageStatus?,
    mutedColor: Color,
    readColor: Color,
    modifier: Modifier = Modifier,
) {
    // Crossfade so the tick "turns" as delivery progresses instead of teleporting.
    Crossfade(
        targetState = status,
        animationSpec = tween(ZappMotion.CONTENT_MS, easing = ZappMotion.easing),
        modifier = modifier,
        label = "messageStatus",
    ) { target ->
        val glyph: String
        val descriptionRes: Int
        val tickCount: Int
        val color =
            when (target) {
                MessageStatus.READ -> {
                    glyph = "✓"
                    descriptionRes = R.string.chat_message_status_read
                    tickCount = 2
                    readColor
                }

                MessageStatus.DELIVERED -> {
                    glyph = "✓"
                    descriptionRes = R.string.chat_message_status_delivered
                    tickCount = 2
                    mutedColor
                }

                MessageStatus.FAILED -> {
                    glyph = "!"
                    descriptionRes = R.string.chat_message_status_failed
                    tickCount = 1
                    ZappTheme.colors.danger
                }

                MessageStatus.QUEUED -> {
                    glyph = "◷"
                    descriptionRes = R.string.chat_message_status_queued
                    tickCount = 1
                    mutedColor
                }

                MessageStatus.SENDING -> {
                    glyph = "◌"
                    descriptionRes = R.string.chat_message_status_sending
                    tickCount = 1
                    mutedColor
                }

                else -> {
                    glyph = "✓"
                    descriptionRes = R.string.chat_message_status_sent
                    tickCount = 1
                    mutedColor
                }
            }
        val description = stringResource(descriptionRes)
        val textStyle =
            ZappTheme.typography.caption.copy(
                fontSize = if (target == MessageStatus.SENDING) 11.sp else 10.sp,
                color = color,
                fontWeight = FontWeight.Black,
            )

        if (tickCount > 1) {
            Box(
                modifier =
                    Modifier
                        .width((7 + (tickCount - 1) * 4).dp)
                        .clearAndSetSemantics { contentDescription = description },
            ) {
                repeat(tickCount) { index ->
                    BasicText(
                        text = glyph,
                        style = textStyle,
                        modifier = Modifier.offset(x = (index * 4).dp),
                    )
                }
            }
        } else {
            BasicText(
                text = glyph,
                style = textStyle,
                modifier = Modifier.semantics { contentDescription = description },
            )
        }
    }
}
