package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.animation.ZappMotion
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import kotlinx.coroutines.delay

/**
 * The terminal-success moment every money flow lands on: the animated badge over a centred headline
 * and explanation. Offramp orders, the top-up bridge and onramp orders all render this so a
 * completed payment reads the same whichever direction the money went.
 */
@Composable
fun ZappSuccessHeader(
    title: StringResource,
    modifier: Modifier = Modifier,
    subtitle: StringResource? = null,
) {
    val c = ZappTheme.colors
    val t = ZappTheme.typography
    val headline = title.getValue()
    val copyIn = remember { Animatable(0f) }
    val copyTravelPx = with(LocalDensity.current) { HEADER_COPY_TRAVEL.dp.toPx() }
    LaunchedEffect(headline) {
        copyIn.snapTo(0f)
        delay(HEADER_COPY_DELAY_MS.toLong())
        copyIn.animateTo(1f, tween(ZappMotion.CONTENT_MS, easing = ZappMotion.easing))
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ZappSuccessBadge()
        Spacer(modifier = Modifier.height(HEADER_GAP.dp))
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .alpha(copyIn.value)
                    .graphicsLayer { translationY = copyTravelPx * (1f - copyIn.value) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                text = headline,
                style = t.display.copy(color = c.text, textAlign = TextAlign.Center),
                modifier = Modifier.fillMaxWidth().semantics { heading() },
            )
            subtitle?.let { sub ->
                Spacer(modifier = Modifier.height(SUBTITLE_GAP.dp))
                BasicText(
                    text = sub.getValue(),
                    style = t.body.copy(color = c.textMuted, textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth(SUBTITLE_WIDTH),
                )
            }
        }
    }
}

private const val HEADER_GAP = 18
private const val SUBTITLE_GAP = 6
private const val SUBTITLE_WIDTH = 0.86f
private const val HEADER_COPY_TRAVEL = 8
private const val HEADER_COPY_DELAY_MS = 150
