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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.animation.ZappMotion
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/**
 * Terminal-success CTA. The check is drawn once as the button arrives, echoing the larger success
 * mark without replaying its celebration or adding a decorative gloss over a primary action.
 */
@Composable
fun ZappDoneButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = ZappTheme.colors
    val completion = c.completion
    val onCompletion = c.onCompletion
    val checkTrim = remember { Animatable(0f) }
    val interactionSource = remember { MutableInteractionSource() }
    LaunchedEffect(Unit) {
        checkTrim.animateTo(1f, tween(ZappMotion.REVEAL_MS, easing = ZappMotion.easing))
    }
    Box(
        modifier =
            modifier
                .defaultMinSize(minHeight = MIN_HEIGHT.dp)
                .background(completion)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = onCompletion),
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
                    color = onCompletion,
                    strokeWidth = size.minDimension * CHECK_STROKE_FRAC,
                )
            }
            BasicText(text = text, style = ZappTheme.typography.button.copy(color = onCompletion))
        }
    }
}

private const val MIN_HEIGHT = 52
private const val H_PADDING = 18
private const val V_PADDING = 14
private const val LABEL_GAP = 6
private const val CHECK_SIZE = 16
private const val CHECK_STROKE_FRAC = 0.12f
