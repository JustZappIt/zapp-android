package co.electriccoin.zcash.ui.screen.swap.upi.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.TX_HASH_ELLIPSIS_PREFIX
import co.electriccoin.zcash.ui.design.component.zapp.TX_HASH_ELLIPSIS_SUFFIX
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappDoneButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappExplorerLink
import co.electriccoin.zcash.ui.design.component.zapp.ZappStep
import co.electriccoin.zcash.ui.design.component.zapp.ZappStepList
import co.electriccoin.zcash.ui.design.component.zapp.ZappStepStatus
import co.electriccoin.zcash.ui.design.component.zapp.ZappSuccessHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappSummaryRow
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.delay

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
                ZappSuccessHeader(title = state.title, subtitle = state.subtitle)
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
            ZappStepList(state.steps)

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
                            ZappDoneButton(
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
        ZappSummaryRow(
            label = stringResource(R.string.upi_offramp_summary_amount),
            value = summary.amountUsdcDisplay.getValue(),
        )
        Spacer(modifier = Modifier.height(GAP_SM.dp))
        ZappSummaryRow(
            label = stringResource(R.string.upi_offramp_summary_recipient),
            value = summary.recipient.getValue(),
        )
        summary.orderId?.let { orderId ->
            Spacer(modifier = Modifier.height(GAP_SM.dp))
            ZappSummaryRow(
                label = stringResource(R.string.upi_offramp_summary_order_id),
                value = stringResource(R.string.upi_offramp_summary_order_id_value, orderId),
            )
        }
        summary.completionDuration?.let { duration ->
            Spacer(modifier = Modifier.height(GAP_SM.dp))
            ZappSummaryRow(
                label = stringResource(R.string.upi_offramp_summary_completion),
                value = duration.getValue(),
            )
        }
        summary.terminalTimestamp?.let { ts ->
            Spacer(modifier = Modifier.height(GAP_SM.dp))
            ZappSummaryRow(
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
        ZappSummaryRow(
            label = stringResource(R.string.upi_offramp_fee_breakdown_you_send),
            value = fees?.youSend?.getValue() ?: summary.amountUsdcDisplay.getValue(),
        )
        fees?.fee?.let {
            Spacer(modifier = Modifier.height(GAP_SM.dp))
            ZappSummaryRow(
                label = stringResource(R.string.upi_offramp_fee_breakdown_fee),
                value = it.getValue(),
            )
        }
        fees?.youReceive?.let {
            Spacer(modifier = Modifier.height(GAP_SM.dp))
            ZappSummaryRow(
                label = stringResource(R.string.upi_offramp_fee_breakdown_you_receive),
                value = it.getValue(),
            )
        }
        Spacer(modifier = Modifier.height(GAP_MD.dp))
        Box(modifier = Modifier.fillMaxWidth().height(DIVIDER_HEIGHT.dp).background(c.border))
        Spacer(modifier = Modifier.height(GAP_MD.dp))
        ZappSummaryRow(
            label = stringResource(R.string.upi_offramp_summary_recipient),
            value = summary.recipient.getValue(),
        )
        summary.orderId?.let { orderId ->
            Spacer(modifier = Modifier.height(GAP_SM.dp))
            ZappSummaryRow(
                label = stringResource(R.string.upi_offramp_summary_order_id),
                value = stringResource(R.string.upi_offramp_summary_order_id_value, orderId),
            )
        }
        summary.completionDuration?.let { duration ->
            Spacer(modifier = Modifier.height(GAP_SM.dp))
            ZappSummaryRow(
                label = stringResource(R.string.upi_offramp_summary_completion),
                value = duration.getValue(),
            )
        }
        summary.terminalTimestamp?.let { timestamp ->
            Spacer(modifier = Modifier.height(GAP_SM.dp))
            ZappSummaryRow(
                label = stringResource(R.string.upi_offramp_summary_time),
                value = timestamp.getValue(),
            )
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
            ZappSummaryRow(stringResource(R.string.upi_offramp_fee_breakdown_you_send), it.getValue())
        }
        fees.fee?.let {
            Spacer(modifier = Modifier.height(GAP_SM.dp))
            ZappSummaryRow(stringResource(R.string.upi_offramp_fee_breakdown_fee), it.getValue())
        }
        fees.youReceive?.let {
            Spacer(modifier = Modifier.height(GAP_SM.dp))
            ZappSummaryRow(stringResource(R.string.upi_offramp_fee_breakdown_you_receive), it.getValue())
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
                ZappExplorerLink(
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
private const val COMPLETION_SCROLL_DELAY_MS = 80L

private val previewSummary =
    UpiOfframpOrderSummary(
        amountUsdcDisplay = stringRes("5.00"),
        recipient = stringRes("merchant@upi"),
        orderId = "12345",
    )

private fun previewSteps(
    funding: ZappStepStatus = ZappStepStatus.Completed,
    approving: ZappStepStatus = ZappStepStatus.Completed,
    placing: ZappStepStatus = ZappStepStatus.Completed,
    waitingAcceptance: ZappStepStatus = ZappStepStatus.Completed,
    sendingUpi: ZappStepStatus = ZappStepStatus.Completed,
    waitingCompletion: ZappStepStatus = ZappStepStatus.Completed,
): List<ZappStep> =
    listOf(
        ZappStep(stringRes("Picking a merchant pool"), ZappStepStatus.Completed),
        ZappStep(stringRes("Bridging funds"), funding),
        ZappStep(stringRes("Approving USDC"), approving),
        ZappStep(stringRes("Placing the order"), placing),
        ZappStep(stringRes("Waiting for merchant to accept"), waitingAcceptance),
        ZappStep(stringRes("Sending encrypted UPI"), sendingUpi),
        ZappStep(stringRes("Waiting for merchant payment"), waitingCompletion),
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
                            placing = ZappStepStatus.InProgress,
                            waitingAcceptance = ZappStepStatus.Pending,
                            sendingUpi = ZappStepStatus.Pending,
                            waitingCompletion = ZappStepStatus.Pending,
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
                            funding = ZappStepStatus.InProgress,
                            approving = ZappStepStatus.Pending,
                            placing = ZappStepStatus.Pending,
                            waitingAcceptance = ZappStepStatus.Pending,
                            sendingUpi = ZappStepStatus.Pending,
                            waitingCompletion = ZappStepStatus.Pending,
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
                            waitingAcceptance = ZappStepStatus.InProgress,
                            sendingUpi = ZappStepStatus.Pending,
                            waitingCompletion = ZappStepStatus.Pending,
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
                            waitingAcceptance = ZappStepStatus.Completed,
                            sendingUpi = ZappStepStatus.Completed,
                            waitingCompletion = ZappStepStatus.Failed,
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
                            funding = ZappStepStatus.Completed,
                            approving = ZappStepStatus.Failed,
                            placing = ZappStepStatus.Pending,
                            waitingAcceptance = ZappStepStatus.Pending,
                            sendingUpi = ZappStepStatus.Pending,
                            waitingCompletion = ZappStepStatus.Pending,
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
                            funding = ZappStepStatus.Completed,
                            approving = ZappStepStatus.Completed,
                            placing = ZappStepStatus.Failed,
                            waitingAcceptance = ZappStepStatus.Pending,
                            sendingUpi = ZappStepStatus.Pending,
                            waitingCompletion = ZappStepStatus.Pending,
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
