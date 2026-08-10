package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.ellipsizeMiddle
import co.electriccoin.zcash.ui.design.util.getValue
import kotlinx.coroutines.delay

enum class ZappStepStatus { Pending, InProgress, Completed, Failed }

data class ZappStep(
    val label: StringResource,
    val status: ZappStepStatus,
    val txHash: String? = null,
    val txExplorerUrl: String? = null,
    val detailLines: List<StringResource> = emptyList(),
)

/**
 * Vertical progress spine: one row per step, joined by a rule that fills as each completes. The
 * single stepper for every multi-step money flow — offramp orders, the top-up bridge, and onramp
 * orders all render through this so a user sees one shape.
 */
@Composable
fun ZappStepList(
    steps: List<ZappStep>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        steps.forEachIndexed { idx, step ->
            ZappStepRow(step, isLast = idx == steps.lastIndex)
        }
    }
}

@Composable
fun ZappStepRow(
    step: ZappStep,
    isLast: Boolean = true,
) {
    val c = ZappTheme.colors
    val t = ZappTheme.typography
    val uriHandler = LocalUriHandler.current
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(step.status) {
        if (step.status == ZappStepStatus.InProgress || step.status == ZappStepStatus.Failed) {
            delay(ACTIVE_STEP_SCROLL_DELAY_MS)
            bringIntoViewRequester.bringIntoView()
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoViewRequester),
        verticalAlignment = Alignment.Top,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            StepIndicator(step.status)
            if (!isLast) {
                Box(
                    modifier =
                        Modifier
                            .width(SPINE_WIDTH.dp)
                            .height(SPINE_HEIGHT.dp)
                            .background(
                                if (step.status == ZappStepStatus.Completed) c.accent else c.border,
                                RectangleShape,
                            ),
                )
            }
        }
        Spacer(modifier = Modifier.width(STEP_GAP.dp))
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = step.label.getValue(),
                style =
                    t.body.copy(
                        color =
                            when (step.status) {
                                ZappStepStatus.Failed -> c.danger
                                ZappStepStatus.Pending -> c.textMuted
                                else -> c.text
                            },
                        fontWeight =
                            when (step.status) {
                                ZappStepStatus.InProgress -> FontWeight.SemiBold
                                else -> FontWeight.Normal
                            },
                    ),
            )
            step.detailLines.forEach { detail ->
                Spacer(modifier = Modifier.height(DETAIL_GAP.dp))
                BasicText(
                    text = detail.getValue(),
                    style = t.caption.copy(color = c.textMuted),
                )
            }
            if (step.txHash != null && step.txExplorerUrl != null) {
                Spacer(modifier = Modifier.height(LINK_GAP.dp))
                ZappExplorerLink(
                    value = step.txHash,
                    url = step.txExplorerUrl,
                    prefix = TX_HASH_ELLIPSIS_PREFIX,
                    suffix = TX_HASH_ELLIPSIS_SUFFIX,
                    uriHandler = uriHandler,
                )
            }
        }
    }
}

@Composable
private fun StepIndicator(status: ZappStepStatus) {
    val c = ZappTheme.colors
    Box(
        modifier =
            Modifier
                .padding(top = STEP_PROGRESS_TOP_OFFSET.dp)
                .size(STEP_PROGRESS_SIZE.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (status != ZappStepStatus.InProgress) {
            val color =
                when (status) {
                    ZappStepStatus.Pending -> c.border
                    ZappStepStatus.Completed -> c.accent
                    ZappStepStatus.Failed -> c.danger
                    ZappStepStatus.InProgress -> c.accent
                }
            Box(
                modifier =
                    Modifier
                        .size(STEP_INDICATOR_SIZE.dp)
                        .background(
                            if (status == ZappStepStatus.Pending) Color.Transparent else color,
                            RectangleShape,
                        ).border(BorderStroke(1.dp, color), RectangleShape),
            )
        } else {
            CircularProgressIndicator(
                color = c.accent,
                strokeWidth = STEP_PROGRESS_STROKE.dp,
                modifier = Modifier.size(STEP_PROGRESS_SIZE.dp),
            )
        }
    }
}

/** Ellipsized, tappable monospace link to a block explorer (address or tx hash). */
@Composable
fun ZappExplorerLink(
    value: String,
    url: String,
    prefix: Int,
    suffix: Int,
    uriHandler: UriHandler,
) {
    val c = ZappTheme.colors
    val t = ZappTheme.typography
    BasicText(
        text = value.ellipsizeMiddle(prefix, suffix),
        style = t.mono.copy(color = c.accent, textDecoration = TextDecoration.Underline),
        modifier =
            Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = c.accent),
                onClick = { uriHandler.openUri(url) },
            ),
    )
}

private const val STEP_GAP = 10
private const val DETAIL_GAP = 2
private const val LINK_GAP = 4
private const val STEP_INDICATOR_SIZE = 12
private const val STEP_PROGRESS_SIZE = 14
private const val STEP_PROGRESS_STROKE = 2
private const val ACTIVE_STEP_SCROLL_DELAY_MS = 80L
private const val STEP_PROGRESS_TOP_OFFSET = 4
private const val SPINE_WIDTH = 2
private const val SPINE_HEIGHT = 22
const val TX_HASH_ELLIPSIS_PREFIX = 12
const val TX_HASH_ELLIPSIS_SUFFIX = 8
const val ADDRESS_ELLIPSIS_PREFIX = 10
const val ADDRESS_ELLIPSIS_SUFFIX = 8
