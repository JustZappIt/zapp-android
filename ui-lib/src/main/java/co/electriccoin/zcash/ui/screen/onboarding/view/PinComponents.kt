package co.electriccoin.zcash.ui.screen.onboarding.view

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.design.animation.ZappMotion
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/** Six square dots that fill as the user enters each digit. */
@Composable
internal fun PinDotRow(
    filledCount: Int,
    hasError: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(6) { i ->
            PinDot(filled = i < filledCount, hasError = hasError)
        }
    }
}

@Composable
private fun PinDot(
    filled: Boolean,
    hasError: Boolean,
) {
    val c = ZappTheme.colors
    val color by
        animateColorAsState(
            targetValue =
                when {
                    hasError -> c.danger
                    filled -> c.text
                    else -> c.border
                },
            animationSpec = tween(durationMillis = ZappMotion.STATE_MS, easing = ZappMotion.easing),
            label = "pinDotColor",
        )
    val scale = remember { Animatable(1f) }
    LaunchedEffect(filled) {
        if (filled) {
            scale.snapTo(DOT_POP_SCALE)
            scale.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = ZappMotion.STATE_MS, easing = ZappMotion.easing),
            )
        }
    }
    Box(
        modifier =
            Modifier
                .size(14.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                }.background(color, RectangleShape),
    )
}

/** Standard phone-layout numeric keypad (1-9, blank, 0, ⌫). */
@Composable
internal fun PinKeypad(modifier: Modifier = Modifier, onKey: (String) -> Unit) {
    val rows =
        listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf(null, "0", "⌫"),
        )
    val haptic = LocalHapticFeedback.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { key ->
                    if (key == null) {
                        Box(modifier = Modifier.weight(1f).height(60.dp))
                    } else {
                        PinKey(
                            key = key,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                runCatching { haptic.performHapticFeedback(HapticFeedbackType.VirtualKey) }
                                onKey(key)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PinKey(
    key: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = ZappTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // Invert instantly on press, fade back on release so even the fastest tap flashes.
    val bg by
        animateColorAsState(
            targetValue = if (pressed) c.text else Color.Transparent,
            animationSpec = tween(durationMillis = if (pressed) 0 else KEY_RELEASE_FADE_MS),
            label = "pinKeyBg",
        )
    val fg by
        animateColorAsState(
            targetValue = if (pressed) c.bg else c.text,
            animationSpec = tween(durationMillis = if (pressed) 0 else KEY_RELEASE_FADE_MS),
            label = "pinKeyFg",
        )
    Box(
        modifier =
            modifier
                .height(60.dp)
                .background(bg, RectangleShape)
                .border(1.dp, c.border, RectangleShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = key,
            style =
                ZappTheme.typography.button.copy(
                    color = fg,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                ),
        )
    }
}

private const val DOT_POP_SCALE = 1.35f
private const val KEY_RELEASE_FADE_MS = 180
