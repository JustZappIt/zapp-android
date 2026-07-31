package co.electriccoin.zcash.ui.screen.swap.upi.progress

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.animation.ZappMotion
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun UpiOfframpProgressView(state: UpiOfframpProgressState) {
    val c = ZappTheme.colors
    val scrollState = rememberScrollState()
    // Reveal the success moment: on completion, glide back to the top so the badge + headline land.
    LaunchedEffect(state.isCompleted) {
        if (state.isCompleted) {
            delay(COMPLETION_SCROLL_DELAY_MS)
            scrollState.animateScrollTo(0)
        }
    }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = HORIZONTAL_PADDING.dp, vertical = VERTICAL_PADDING.dp),
        ) {
            if (state.isCompleted) {
                CompletedSuccessHeader(title = state.title, subtitle = state.subtitle)
            } else {
                BasicText(
                    text = state.title.getValue(),
                    style = ZappTheme.typography.display.copy(color = c.text),
                )
                state.subtitle?.let { sub ->
                    Spacer(modifier = Modifier.height(GAP_SM.dp))
                    BasicText(
                        text = sub.getValue(),
                        style = ZappTheme.typography.body.copy(color = c.textMuted),
                    )
                }
            }

            if (state.isCompleted && state.summary != null) {
                Spacer(modifier = Modifier.height(GAP_LG.dp))
                CompletedOrderBreakdownCard(summary = state.summary, fees = state.feeBreakdown)
            } else {
                state.summary?.let { summary ->
                    Spacer(modifier = Modifier.height(GAP_LG.dp))
                    OrderSummaryCard(summary)
                }

                state.feeBreakdown?.let { fees ->
                    Spacer(modifier = Modifier.height(GAP_LG.dp))
                    FeeBreakdownCard(fees)
                }
            }

            Spacer(modifier = Modifier.height(GAP_LG.dp))
            OfframpStepList(state.steps)

            state.cancelled?.let { cancelled ->
                Spacer(modifier = Modifier.height(GAP_LG.dp))
                CancelledCard(cancelled)
            }

            state.failure?.let { failure ->
                Spacer(modifier = Modifier.height(GAP_LG.dp))
                FailureCard(failure)
            }
        }
        ZappBottomActionBar(
            onBack = state.onBack,
            primaryAction =
                state.primaryButton?.let { btn ->
                    {
                        if (state.isCompleted) {
                            CompletedDoneButton(
                                text = btn.text.getValue(),
                                modifier = Modifier.weight(1f).padding(start = BOTTOM_BAR_GAP.dp),
                                onClick = btn.onClick,
                            )
                        } else {
                            ZappButton(
                                text = btn.text.getValue(),
                                enabled = btn.isEnabled,
                                variant = ZappButtonVariant.Primary,
                                modifier = Modifier.weight(1f).padding(start = BOTTOM_BAR_GAP.dp),
                                onClick = btn.onClick,
                            )
                        }
                    }
                },
        )
    }
}

@Composable
private fun OrderSummaryCard(summary: UpiOfframpOrderSummary) {
    ZappBorderedCard {
        SummaryRow(
            label = stringResource(R.string.upi_offramp_summary_amount),
            value = summary.amountUsdcDisplay.getValue(),
        )
        Spacer(modifier = Modifier.height(GAP_SM.dp))
        SummaryRow(
            label = stringResource(R.string.upi_offramp_summary_recipient),
            value = summary.recipient.getValue(),
        )
        summary.orderId?.let { orderId ->
            Spacer(modifier = Modifier.height(GAP_SM.dp))
            SummaryRow(
                label = stringResource(R.string.upi_offramp_summary_order_id),
                value = stringResource(R.string.upi_offramp_summary_order_id_value, orderId),
            )
        }
        summary.completionDuration?.let { duration ->
            Spacer(modifier = Modifier.height(GAP_SM.dp))
            SummaryRow(
                label = stringResource(R.string.upi_offramp_summary_completion),
                value = duration.getValue(),
            )
        }
        summary.terminalTimestamp?.let { ts ->
            Spacer(modifier = Modifier.height(GAP_SM.dp))
            SummaryRow(
                label = stringResource(R.string.upi_offramp_summary_time),
                value = ts.getValue(),
            )
        }
    }
}

@Composable
private fun CompletedOrderBreakdownCard(
    summary: UpiOfframpOrderSummary,
    fees: UpiOfframpFeeBreakdown?,
) {
    val c = ZappTheme.colors
    val t = ZappTheme.typography
    ZappBorderedCard {
        BasicText(
            text = stringResource(R.string.upi_offramp_fee_breakdown_header),
            style = t.button.copy(color = c.text, fontWeight = FontWeight.SemiBold),
        )
        Spacer(modifier = Modifier.height(GAP_SM.dp))
        SummaryRow(
            label = stringResource(R.string.upi_offramp_fee_breakdown_you_send),
            value = fees?.youSend?.getValue() ?: summary.amountUsdcDisplay.getValue(),
        )
        fees?.fee?.let {
            Spacer(modifier = Modifier.height(GAP_SM.dp))
            SummaryRow(
                label = stringResource(R.string.upi_offramp_fee_breakdown_fee),
                value = it.getValue(),
            )
        }
        fees?.youReceive?.let {
            Spacer(modifier = Modifier.height(GAP_SM.dp))
            SummaryRow(
                label = stringResource(R.string.upi_offramp_fee_breakdown_you_receive),
                value = it.getValue(),
            )
        }
        Spacer(modifier = Modifier.height(GAP_MD.dp))
        Box(modifier = Modifier.fillMaxWidth().height(DIVIDER_HEIGHT.dp).background(c.border))
        Spacer(modifier = Modifier.height(GAP_MD.dp))
        SummaryRow(
            label = stringResource(R.string.upi_offramp_summary_recipient),
            value = summary.recipient.getValue(),
        )
        summary.orderId?.let { orderId ->
            Spacer(modifier = Modifier.height(GAP_SM.dp))
            SummaryRow(
                label = stringResource(R.string.upi_offramp_summary_order_id),
                value = stringResource(R.string.upi_offramp_summary_order_id_value, orderId),
            )
        }
        summary.completionDuration?.let { duration ->
            Spacer(modifier = Modifier.height(GAP_SM.dp))
            SummaryRow(
                label = stringResource(R.string.upi_offramp_summary_completion),
                value = duration.getValue(),
            )
        }
        summary.terminalTimestamp?.let { timestamp ->
            Spacer(modifier = Modifier.height(GAP_SM.dp))
            SummaryRow(
                label = stringResource(R.string.upi_offramp_summary_time),
                value = timestamp.getValue(),
            )
        }
    }
}

// Shared draw helper: a checkmark stroked on via path-trim (progress 0..1) inside a `side`-square at
// [topLeft]. Square caps + miter joint keep it Swiss-crisp. Used by the success badge and Done button.
private fun DrawScope.drawTrimmedCheck(
    topLeft: Offset,
    side: Float,
    progress: Float,
    color: Color,
    strokeWidth: Float,
) {
    if (progress <= 0f) return
    val path =
        Path().apply {
            moveTo(topLeft.x + side * CHECK_P0_X, topLeft.y + side * CHECK_P0_Y)
            lineTo(topLeft.x + side * CHECK_P1_X, topLeft.y + side * CHECK_P1_Y)
            lineTo(topLeft.x + side * CHECK_P2_X, topLeft.y + side * CHECK_P2_Y)
        }
    val measure = PathMeasure().apply { setPath(path, false) }
    val segment = Path()
    measure.getSegment(0f, measure.length * progress, segment, true)
    drawPath(
        path = segment,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Square, join = StrokeJoin.Miter),
    )
}

// Swiss success badge: a sharp accent square stamps in, the checkmark strokes on, and a single square
// outline rings outward once and fades. Crisp tweens only (ZappMotion) — no springs, no overshoot.
@Composable
private fun AnimatedCheckBadge(modifier: Modifier = Modifier) {
    val c = ZappTheme.colors
    val accent = c.accent
    val onAccent = c.onAccent
    val badgeIn = remember { Animatable(0f) }
    val checkTrim = remember { Animatable(0f) }
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        badgeIn.animateTo(1f, tween(ZappMotion.CONTENT_MS, easing = ZappMotion.easing))
        launch { pulse.animateTo(1f, tween(COMPLETE_PULSE_MS, easing = ZappMotion.easing)) }
        checkTrim.animateTo(1f, tween(ZappMotion.REVEAL_MS, easing = ZappMotion.easing))
    }
    Canvas(modifier = modifier.size(COMPLETE_CANVAS_SIZE.dp)) {
        val badge = COMPLETE_MARK_SIZE.dp.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f

        if (pulse.value > 0f) {
            val side = badge * (1f + COMPLETE_PULSE_GROW * pulse.value)
            drawRect(
                color = accent,
                topLeft = Offset(cx - side / 2f, cy - side / 2f),
                size = Size(side, side),
                alpha = (1f - pulse.value) * COMPLETE_PULSE_MAX_ALPHA,
                style = Stroke(width = COMPLETE_PULSE_STROKE.dp.toPx()),
            )
        }

        val scaled = badge * (COMPLETE_MARK_INITIAL_SCALE + (1f - COMPLETE_MARK_INITIAL_SCALE) * badgeIn.value)
        drawRect(
            color = accent,
            topLeft = Offset(cx - scaled / 2f, cy - scaled / 2f),
            size = Size(scaled, scaled),
            alpha = badgeIn.value,
        )

        drawTrimmedCheck(
            topLeft = Offset(cx - badge / 2f, cy - badge / 2f),
            side = badge,
            progress = checkTrim.value,
            color = onAccent,
            strokeWidth = badge * COMPLETE_CHECK_STROKE_FRAC,
        )
    }
}

// Centered success moment shown at the top on completion: the animated badge over the "Payment
// complete" headline + explanation, so the terminal state reads unmistakably as done.
@Composable
private fun CompletedSuccessHeader(
    title: StringResource,
    subtitle: StringResource?,
) {
    val c = ZappTheme.colors
    val t = ZappTheme.typography
    val completedLabel = title.getValue()
    Column(
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = completedLabel },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedCheckBadge()
        Spacer(modifier = Modifier.height(SUCCESS_HEADER_GAP.dp))
        BasicText(
            text = completedLabel,
            style = t.display.copy(color = c.text, textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth(),
        )
        subtitle?.let { sub ->
            Spacer(modifier = Modifier.height(GAP_SM.dp))
            BasicText(
                text = sub.getValue(),
                style = t.body.copy(color = c.textMuted, textAlign = TextAlign.Center),
                modifier = Modifier.fillMaxWidth(SUCCESS_SUBTITLE_WIDTH),
            )
        }
    }
}

// Completed-state primary CTA: the checkmark strokes on and a single light gloss sweeps across the
// accent button once, so the resolved action celebrates the payment. The gloss is white (not a token)
// because the accent is the same orange in light + dark, so a light sheen reads correctly in both.
@Composable
private fun CompletedDoneButton(
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
        shine.animateTo(1f, tween(COMPLETE_SHINE_MS, easing = ZappMotion.easing))
    }
    Box(
        modifier =
            modifier
                .defaultMinSize(minHeight = DONE_BUTTON_MIN_HEIGHT.dp)
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
                                    0.5f to Color.White.copy(alpha = SHINE_ALPHA),
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
                }.padding(horizontal = DONE_BUTTON_H_PADDING.dp, vertical = DONE_BUTTON_V_PADDING.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GAP_SM.dp),
        ) {
            Canvas(modifier = Modifier.size(DONE_CHECK_SIZE.dp)) {
                drawTrimmedCheck(
                    topLeft = Offset.Zero,
                    side = size.minDimension,
                    progress = checkTrim.value,
                    color = onAccent,
                    strokeWidth = size.minDimension * DONE_CHECK_STROKE_FRAC,
                )
            }
            BasicText(text = text, style = ZappTheme.typography.button.copy(color = onAccent))
        }
    }
}

@Composable
private fun FeeBreakdownCard(fees: UpiOfframpFeeBreakdown) {
    val c = ZappTheme.colors
    val t = ZappTheme.typography
    ZappBorderedCard {
        BasicText(
            text = stringResource(R.string.upi_offramp_fee_breakdown_header),
            style = t.button.copy(color = c.text, fontWeight = FontWeight.SemiBold),
        )
        fees.youSend?.let {
            Spacer(modifier = Modifier.height(GAP_SM.dp))
            SummaryRow(stringResource(R.string.upi_offramp_fee_breakdown_you_send), it.getValue())
        }
        fees.fee?.let {
            Spacer(modifier = Modifier.height(GAP_SM.dp))
            SummaryRow(stringResource(R.string.upi_offramp_fee_breakdown_fee), it.getValue())
        }
        fees.youReceive?.let {
            Spacer(modifier = Modifier.height(GAP_SM.dp))
            SummaryRow(stringResource(R.string.upi_offramp_fee_breakdown_you_receive), it.getValue())
        }
    }
}

@Composable
private fun CancelledCard(cancelled: UpiOfframpCancelledCard) {
    val c = ZappTheme.colors
    val t = ZappTheme.typography
    ZappBorderedCard {
        BasicText(
            text = cancelled.reassurance.getValue(),
            style = t.body.copy(color = c.text, fontWeight = FontWeight.SemiBold),
        )
        cancelled.cancelledAt?.let {
            Spacer(modifier = Modifier.height(GAP_SM.dp))
            BasicText(
                text = it.getValue(),
                style = t.caption.copy(color = c.textMuted),
            )
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    val c = ZappTheme.colors
    val t = ZappTheme.typography
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = label,
            style = t.caption.copy(color = c.textMuted, fontWeight = FontWeight.Medium),
        )
        BasicText(
            text = value,
            style = t.body.copy(color = c.text, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = GAP_MD.dp),
        )
    }
}

@Composable
private fun FailureCard(failure: UpiOfframpFailureCard) {
    val c = ZappTheme.colors
    val t = ZappTheme.typography
    val uriHandler = LocalUriHandler.current
    ZappBorderedCard(borderColor = c.danger) {
        BasicText(
            text = stringResource(R.string.upi_offramp_failure_header, failure.stepLabel.getValue()),
            style = t.button.copy(color = c.danger, fontWeight = FontWeight.SemiBold),
        )
        failure.decodedReason?.let { reason ->
            Spacer(modifier = Modifier.height(GAP_SM.dp))
            BasicText(
                text = reason.getValue(),
                style = t.body.copy(color = c.text),
            )
        }
        if (failure.txHash != null && failure.txExplorerUrl != null) {
            Spacer(modifier = Modifier.height(GAP_SM.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = stringResource(R.string.upi_offramp_failure_transaction),
                    style = t.caption.copy(color = c.textMuted, fontWeight = FontWeight.Medium),
                )
                Spacer(modifier = Modifier.size(GAP_MD.dp))
                OfframpExplorerLink(
                    value = failure.txHash,
                    url = failure.txExplorerUrl,
                    prefix = TX_HASH_ELLIPSIS_PREFIX,
                    suffix = TX_HASH_ELLIPSIS_SUFFIX,
                    uriHandler = uriHandler,
                )
            }
        }
    }
}

private const val HORIZONTAL_PADDING = 18
private const val VERTICAL_PADDING = 16
private const val BOTTOM_BAR_GAP = 12
private const val GAP_SM = 6
private const val GAP_MD = 10
private const val GAP_LG = 20
private const val DIVIDER_HEIGHT = 1
private const val SUCCESS_HEADER_GAP = 18
private const val SUCCESS_SUBTITLE_WIDTH = 0.86f
private const val COMPLETE_CANVAS_SIZE = 120
private const val COMPLETE_MARK_SIZE = 64
private const val COMPLETE_MARK_INITIAL_SCALE = 0.86f
private const val COMPLETE_CHECK_STROKE_FRAC = 0.08f
private const val COMPLETE_PULSE_GROW = 0.75f
private const val COMPLETE_PULSE_STROKE = 1.5f
private const val COMPLETE_PULSE_MAX_ALPHA = 0.45f
private const val COMPLETE_PULSE_MS = 520
private const val COMPLETION_SCROLL_DELAY_MS = 80L
private const val DONE_BUTTON_MIN_HEIGHT = 52
private const val DONE_BUTTON_H_PADDING = 18
private const val DONE_BUTTON_V_PADDING = 14
private const val DONE_CHECK_SIZE = 16
private const val DONE_CHECK_STROKE_FRAC = 0.12f
private const val COMPLETE_SHINE_MS = 600
private const val SHINE_BAND_FRAC = 0.42f
private const val SHINE_ALPHA = 0.30f
private const val CHECK_P0_X = 0.26f
private const val CHECK_P0_Y = 0.50f
private const val CHECK_P1_X = 0.43f
private const val CHECK_P1_Y = 0.67f
private const val CHECK_P2_X = 0.75f
private const val CHECK_P2_Y = 0.34f

private val previewSummary =
    UpiOfframpOrderSummary(
        amountUsdcDisplay = stringRes("5.00"),
        recipient = stringRes("merchant@upi"),
        orderId = "12345",
    )

private fun previewSteps(
    funding: UpiOfframpStepStatus = UpiOfframpStepStatus.Completed,
    approving: UpiOfframpStepStatus = UpiOfframpStepStatus.Completed,
    placing: UpiOfframpStepStatus = UpiOfframpStepStatus.Completed,
    waitingAcceptance: UpiOfframpStepStatus = UpiOfframpStepStatus.Completed,
    sendingUpi: UpiOfframpStepStatus = UpiOfframpStepStatus.Completed,
    waitingCompletion: UpiOfframpStepStatus = UpiOfframpStepStatus.Completed,
): List<UpiOfframpStep> =
    listOf(
        UpiOfframpStep(stringRes("Picking a merchant pool"), UpiOfframpStepStatus.Completed),
        UpiOfframpStep(stringRes("Bridging funds"), funding),
        UpiOfframpStep(stringRes("Approving USDC"), approving),
        UpiOfframpStep(stringRes("Placing the order"), placing),
        UpiOfframpStep(stringRes("Waiting for merchant to accept"), waitingAcceptance),
        UpiOfframpStep(stringRes("Sending encrypted UPI"), sendingUpi),
        UpiOfframpStep(stringRes("Waiting for merchant payment"), waitingCompletion),
    )

@PreviewScreens
@Composable
private fun PreviewInProgress() {
    ZcashTheme {
        UpiOfframpProgressView(
            state =
                UpiOfframpProgressState(
                    title = stringRes("Sending to merchant"),
                    subtitle = stringRes("Recipient: merchant@upi"),
                    summary = previewSummary,
                    feeBreakdown = null,
                    cancelled = null,
                    steps =
                        previewSteps(
                            placing = UpiOfframpStepStatus.InProgress,
                            waitingAcceptance = UpiOfframpStepStatus.Pending,
                            sendingUpi = UpiOfframpStepStatus.Pending,
                            waitingCompletion = UpiOfframpStepStatus.Pending,
                        ),
                    failure = null,
                    primaryButton = null,
                    onBack = {},
                ),
        )
    }
}

@PreviewScreens
@Composable
private fun PreviewFunding() {
    ZcashTheme {
        UpiOfframpProgressView(
            state =
                UpiOfframpProgressState(
                    title = stringRes("Sending to merchant"),
                    subtitle = stringRes("Recipient: merchant@upi"),
                    summary = previewSummary,
                    feeBreakdown = null,
                    cancelled = null,
                    steps =
                        previewSteps(
                            funding = UpiOfframpStepStatus.InProgress,
                            approving = UpiOfframpStepStatus.Pending,
                            placing = UpiOfframpStepStatus.Pending,
                            waitingAcceptance = UpiOfframpStepStatus.Pending,
                            sendingUpi = UpiOfframpStepStatus.Pending,
                            waitingCompletion = UpiOfframpStepStatus.Pending,
                        ),
                    failure = null,
                    primaryButton = null,
                    onBack = {},
                ),
        )
    }
}

@PreviewScreens
@Composable
private fun PreviewWaitingStalled() {
    // Matches the official client's pay/placed UX: no user action during merchant-search. The
    // contract auto-cancels on its own timer and our refund button only surfaces post-Cancelled.
    ZcashTheme {
        UpiOfframpProgressView(
            state =
                UpiOfframpProgressState(
                    title = stringRes("Sending to merchant"),
                    subtitle = stringRes("Recipient: merchant@upi"),
                    summary = previewSummary,
                    feeBreakdown = null,
                    cancelled = null,
                    steps =
                        previewSteps(
                            waitingAcceptance = UpiOfframpStepStatus.InProgress,
                            sendingUpi = UpiOfframpStepStatus.Pending,
                            waitingCompletion = UpiOfframpStepStatus.Pending,
                        ),
                    failure = null,
                    primaryButton = null,
                    onBack = {},
                ),
        )
    }
}

@PreviewScreens
@Composable
private fun PreviewCompleted() {
    ZcashTheme {
        UpiOfframpProgressView(
            state =
                UpiOfframpProgressState(
                    title = stringRes("Payment sent"),
                    subtitle = stringRes("The merchant has confirmed the UPI transfer."),
                    summary =
                        previewSummary.copy(
                            completionDuration = stringRes("1m 32s"),
                            terminalTimestamp = stringRes("22 May 2026, 02:43 AM"),
                        ),
                    feeBreakdown =
                        UpiOfframpFeeBreakdown(
                            youSend = stringRes("5.000 USDC"),
                            fee = stringRes("0.050 USDC"),
                            youReceive = stringRes("₹445.00"),
                        ),
                    cancelled = null,
                    steps = previewSteps(),
                    failure = null,
                    primaryButton = ButtonState(text = stringRes("Done"), onClick = {}),
                    isCompleted = true,
                    onBack = {},
                ),
        )
    }
}

@PreviewScreens
@Composable
private fun PreviewCancelled() {
    ZcashTheme {
        UpiOfframpProgressView(
            state =
                UpiOfframpProgressState(
                    title = stringRes("Order cancelled"),
                    subtitle = stringRes("No merchant completed this order in time. Your USDC has been refunded on-chain."),
                    summary =
                        previewSummary.copy(
                            terminalTimestamp = stringRes("22 May 2026, 03:51 AM"),
                        ),
                    feeBreakdown = null,
                    cancelled =
                        UpiOfframpCancelledCard(
                            reassurance = stringRes("Your balance remains 5.00 USDC."),
                            cancelledAt = stringRes("Cancelled at 22 May 2026, 03:51 AM"),
                        ),
                    steps =
                        previewSteps(
                            waitingAcceptance = UpiOfframpStepStatus.Completed,
                            sendingUpi = UpiOfframpStepStatus.Completed,
                            waitingCompletion = UpiOfframpStepStatus.Failed,
                        ),
                    failure = null,
                    primaryButton = ButtonState(text = stringRes("Close"), onClick = {}),
                    onBack = {},
                ),
        )
    }
}

@PreviewScreens
@Composable
private fun PreviewFailed() {
    ZcashTheme {
        UpiOfframpProgressView(
            state =
                UpiOfframpProgressState(
                    title = stringRes("Something went wrong"),
                    subtitle = null,
                    summary = previewSummary,
                    feeBreakdown = null,
                    cancelled = null,
                    steps =
                        previewSteps(
                            funding = UpiOfframpStepStatus.Completed,
                            approving = UpiOfframpStepStatus.Failed,
                            placing = UpiOfframpStepStatus.Pending,
                            waitingAcceptance = UpiOfframpStepStatus.Pending,
                            sendingUpi = UpiOfframpStepStatus.Pending,
                            waitingCompletion = UpiOfframpStepStatus.Pending,
                        ),
                    failure =
                        UpiOfframpFailureCard(
                            stepLabel = stringRes("Approving USDC"),
                            decodedReason = stringRes("Insufficient USDC balance for approval."),
                            txHash = "0xabcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
                            txExplorerUrl = "https://sepolia.basescan.org/tx/0xabcdef",
                        ),
                    primaryButton = ButtonState(text = stringRes("Close"), onClick = {}),
                    onBack = {},
                ),
        )
    }
}

@PreviewScreens
@Composable
private fun PreviewFailedRecoverable() {
    ZcashTheme {
        UpiOfframpProgressView(
            state =
                UpiOfframpProgressState(
                    title = stringRes("Something went wrong"),
                    subtitle = null,
                    summary = previewSummary,
                    feeBreakdown = null,
                    cancelled = null,
                    steps =
                        previewSteps(
                            funding = UpiOfframpStepStatus.Completed,
                            approving = UpiOfframpStepStatus.Completed,
                            placing = UpiOfframpStepStatus.Failed,
                            waitingAcceptance = UpiOfframpStepStatus.Pending,
                            sendingUpi = UpiOfframpStepStatus.Pending,
                            waitingCompletion = UpiOfframpStepStatus.Pending,
                        ),
                    failure =
                        UpiOfframpFailureCard(
                            stepLabel = stringRes("Placing the order"),
                            decodedReason = stringRes("No merchant has fiat liquidity for this order right now."),
                            txHash = "0xabcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
                            txExplorerUrl = "https://sepolia.basescan.org/tx/0xabcdef",
                        ),
                    primaryButton = ButtonState(text = stringRes("Bridge USDC back to ZEC"), onClick = {}),
                    onBack = {},
                ),
        )
    }
}
