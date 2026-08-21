package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.animation.ZappMotion
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/**
 * A flat progress bar in the house style: a hard-edged rule, a percentage set in monospace so the
 * digits do not shuffle as they tick, and a ten-part grid that makes the position readable at a
 * glance without reading the number at all.
 *
 * Deliberately not `LinearProgressIndicator`: Material rounds its caps and animates its own way,
 * and this design has square corners everywhere.
 *
 * Pass [fraction] `null` while there is genuinely nothing to report — a scan that has not published
 * a figure yet — and the bar sweeps instead of sitting at a dishonest zero.
 */
@Composable
fun ZappProgressBar(
    fraction: Float?,
    modifier: Modifier = Modifier,
    label: String? = null,
    detail: String? = null,
) {
    val c = ZappTheme.colors
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GAP.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            BasicText(
                text = fraction?.let { "${(it * PERCENT).toInt()}%" } ?: EM_DASH,
                style =
                    ZappTheme.typography.display.copy(
                        // Muted while there is no figure, so the em dash reads as an absent value
                        // rather than as another rule in the layout.
                        color = if (fraction == null) c.textSubtle else c.text,
                        fontWeight = FontWeight.SemiBold,
                    ),
            )
            detail?.let {
                BasicText(
                    text = it,
                    style =
                        ZappTheme.typography.mono.copy(
                            color = c.textMuted,
                            textAlign = TextAlign.End,
                        ),
                )
            }
        }

        Track(fraction)

        label?.let {
            BasicText(
                text = it,
                style = ZappTheme.typography.caption.copy(color = c.textMuted),
            )
        }
    }
}

@Composable
private fun Track(fraction: Float?) {
    val c = ZappTheme.colors
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(TRACK_HEIGHT.dp)
                .background(c.surface, RectangleShape)
                .border(BorderStroke(1.dp, c.border), RectangleShape)
                .clipToBounds(),
    ) {
        if (fraction == null) {
            IndeterminateFill()
        } else {
            val animated by
                animateFloatAsState(
                    targetValue = fraction.coerceIn(0f, 1f),
                    animationSpec = tween(ZappMotion.CONTENT_MS, easing = ZappMotion.easing),
                    label = "zappProgressFill",
                )
            Box(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animated)
                        .background(c.accent, RectangleShape),
            )
        }
        Ticks()
    }
}

/**
 * A sweeping block for the stretch before a real figure exists.
 *
 * Constant speed, no easing: the point is to say "still working", and an eased sweep reads as
 * progress that speeds up and slows down, which would be a claim this makes no attempt to support.
 */
@Composable
private fun IndeterminateFill() {
    val transition = rememberInfiniteTransition(label = "zappProgressSweep")
    val offset by
        transition.animateFloat(
            initialValue = -SWEEP_WIDTH,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(SWEEP_MS, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "zappProgressSweepOffset",
        )
    Box(
        modifier =
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(SWEEP_WIDTH)
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        placeable.placeRelative((offset * constraints.maxWidth).toInt(), 0)
                    }
                }.background(ZappTheme.colors.accent, RectangleShape),
    )
}

/**
 * Nine hairlines cutting the track into tenths.
 *
 * Drawn over the fill in the page colour, so the bar reads as a measured scale rather than a
 * blob — the grid is what makes it Swiss rather than merely square.
 */
@Composable
private fun Ticks() {
    val c = ZappTheme.colors
    Row(modifier = Modifier.fillMaxWidth()) {
        repeat(TICKS) {
            Box(modifier = Modifier.weight(1f))
            if (it < TICKS - 1) {
                Box(
                    modifier =
                        Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(c.bg),
                )
            }
        }
    }
}

private const val PERCENT = 100
private const val TICKS = 10
private const val TRACK_HEIGHT = 12
private const val GAP = 8
private const val SWEEP_WIDTH = 0.28f
private const val SWEEP_MS = 1_100
private const val EM_DASH = "—"
