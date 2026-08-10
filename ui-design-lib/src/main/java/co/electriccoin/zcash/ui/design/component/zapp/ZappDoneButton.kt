package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.animation.ZappMotion
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/**
 * Terminal-success CTA: the checkmark strokes on and a single light gloss sweeps across the accent
 * button once, so the resolved action celebrates the payment. The gloss is white rather than a
 * token because the accent is the same orange in light and dark, so a light sheen reads correctly
 * in both.
 */
@Composable
fun ZappDoneButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = ZappTheme.colors
    val accent = c.accent
    val onAccent = c.onAccent
    val checkTrim = remember { Animatable(0f) }
    val shine = remember { Animatable(0f) }
    val interactionSource = remember { MutableInteractionSource() }
    LaunchedEffect(Unit) {
        checkTrim.animateTo(1f, tween(ZappMotion.REVEAL_MS, easing = ZappMotion.easing))
        shine.animateTo(1f, tween(SHINE_MS, easing = ZappMotion.easing))
    }
    Box(
        modifier =
            modifier
                .defaultMinSize(minHeight = MIN_HEIGHT.dp)
                .background(accent)
                .drawWithContent {
                    drawContent()
                    if (shine.value > 0f && shine.value < 1f) {
                        val bandWidth = size.width * SHINE_BAND_FRAC
                        val start = -bandWidth + (size.width + bandWidth) * shine.value
                        drawRect(
                            brush =
                                Brush.horizontalGradient(
                                    0f to Color.Transparent,
                                    SHINE_BAND_MIDPOINT to Color.White.copy(alpha = SHINE_ALPHA),
                                    1f to Color.Transparent,
                                    startX = start,
                                    endX = start + bandWidth,
                                ),
                        )
                    }
                }.clickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = onAccent),
                    onClick = onClick,
                ).semantics(mergeDescendants = true) {
                    contentDescription = text
                    role = Role.Button
                }.padding(horizontal = H_PADDING.dp, vertical = V_PADDING.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LABEL_GAP.dp),
        ) {
            Canvas(modifier = Modifier.size(CHECK_SIZE.dp)) {
                drawTrimmedCheck(
                    topLeft = Offset.Zero,
                    side = size.minDimension,
                    progress = checkTrim.value,
                    color = onAccent,
                    strokeWidth = size.minDimension * CHECK_STROKE_FRAC,
                )
            }
            BasicText(text = text, style = ZappTheme.typography.button.copy(color = onAccent))
        }
    }
}

private const val MIN_HEIGHT = 52
private const val H_PADDING = 18
private const val V_PADDING = 14
private const val LABEL_GAP = 6
private const val CHECK_SIZE = 16
private const val CHECK_STROKE_FRAC = 0.12f
private const val SHINE_MS = 600
private const val SHINE_BAND_FRAC = 0.42f
private const val SHINE_BAND_MIDPOINT = 0.5f
private const val SHINE_ALPHA = 0.30f
