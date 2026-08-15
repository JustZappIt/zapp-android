package co.electriccoin.zcash.ui.screen.swap.peer.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import co.electriccoin.zcash.ui.design.component.zapp.ZappExplorerLink
import co.electriccoin.zcash.ui.design.component.zapp.ZappStep
import co.electriccoin.zcash.ui.design.component.zapp.ZappStepList
import co.electriccoin.zcash.ui.design.component.zapp.ZappStepStatus
import co.electriccoin.zcash.ui.design.component.zapp.ZappSummaryRow
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes

@Composable
internal fun PeerCashOutProgressView(state: PeerCashOutProgressState) {
    val c = ZappTheme.colors
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
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = HORIZONTAL_PADDING.dp, vertical = VERTICAL_PADDING.dp),
        ) {
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

            state.summary?.let { summary ->
                Spacer(modifier = Modifier.height(GAP_LG.dp))
                OrderSummaryCard(summary)
            }

            Spacer(modifier = Modifier.height(GAP_LG.dp))
            ZappStepList(state.steps)

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
                        ZappButton(
                            text = btn.text.getValue(),
                            enabled = btn.isEnabled,
                            variant = ZappButtonVariant.Primary,
                            modifier = Modifier.weight(1f).padding(start = BOTTOM_BAR_GAP.dp),
                            onClick = btn.onClick,
                        )
                    }
                },
        )
    }
}

@Composable
private fun OrderSummaryCard(summary: PeerCashOutOrderSummary) {
    ZappBorderedCard {
        ZappSummaryRow(
            label = stringResource(R.string.peer_offramp_summary_amount),
            value = summary.amountUsdcDisplay.getValue(),
        )
        Spacer(modifier = Modifier.height(GAP_SM.dp))
        ZappSummaryRow(
            label = stringResource(R.string.peer_offramp_summary_rail),
            value = summary.platform.getValue(),
        )
        Spacer(modifier = Modifier.height(GAP_SM.dp))
        ZappSummaryRow(
            label = stringResource(R.string.peer_offramp_summary_currencies),
            value = summary.currencies.getValue(),
        )
    }
}

@Composable
private fun FailureCard(failure: PeerCashOutFailureCard) {
    val c = ZappTheme.colors
    val t = ZappTheme.typography
    val uriHandler = LocalUriHandler.current
    ZappBorderedCard(borderColor = c.danger) {
        BasicText(
            text = stringResource(R.string.peer_offramp_failure_header, failure.stepLabel.getValue()),
            style = t.button.copy(color = c.danger, fontWeight = FontWeight.SemiBold),
        )
        Spacer(modifier = Modifier.height(GAP_SM.dp))
        BasicText(
            text = failure.reason.getValue(),
            style = t.body.copy(color = c.text),
        )
        if (failure.txHash != null && failure.txExplorerUrl != null) {
            Spacer(modifier = Modifier.height(GAP_SM.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = stringResource(R.string.peer_offramp_failure_transaction),
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
        failure.retry?.let { retry ->
            Spacer(modifier = Modifier.height(GAP_MD.dp))
            ZappButton(
                text = retry.text.getValue(),
                enabled = retry.isEnabled,
                variant = ZappButtonVariant.Secondary,
                modifier = Modifier.fillMaxWidth(),
                onClick = retry.onClick,
            )
        }
    }
}

private const val HORIZONTAL_PADDING = 18
private const val VERTICAL_PADDING = 16
private const val BOTTOM_BAR_GAP = 12
private const val GAP_SM = 6
private const val GAP_MD = 10
private const val GAP_LG = 20

private val previewSummary =
    PeerCashOutOrderSummary(
        amountUsdcDisplay = stringRes("250 USDC"),
        platform = stringRes("Revolut"),
        currencies = stringRes("EUR, GBP, USD"),
    )

private fun previewSteps(
    validating: ZappStepStatus = ZappStepStatus.Completed,
    approving: ZappStepStatus = ZappStepStatus.Completed,
    creating: ZappStepStatus = ZappStepStatus.Completed,
    awaiting: ZappStepStatus = ZappStepStatus.InProgress,
    settling: ZappStepStatus = ZappStepStatus.Pending,
): List<ZappStep> =
    listOf(
        ZappStep(stringRes("Checking your Revolut details"), validating),
        ZappStep(stringRes("Approving USDC"), approving),
        ZappStep(stringRes("Creating your cash-out order"), creating),
        ZappStep(stringRes("Waiting for a buyer"), awaiting),
        ZappStep(stringRes("A buyer is paying you"), settling),
    )

@PreviewScreens
@Composable
private fun PreviewCreating() {
    ZcashTheme {
        PeerCashOutProgressView(
            state =
                PeerCashOutProgressState(
                    title = stringRes("Setting up your cash-out"),
                    subtitle = stringRes("Do not close the app until this confirms"),
                    summary = previewSummary,
                    steps =
                        previewSteps(
                            creating = ZappStepStatus.InProgress,
                            awaiting = ZappStepStatus.Pending,
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
private fun PreviewLive() {
    ZcashTheme {
        PeerCashOutProgressView(
            state =
                PeerCashOutProgressState(
                    title = stringRes("Your order is live"),
                    subtitle = stringRes("You can leave this screen. The order stays open on chain."),
                    summary = previewSummary,
                    steps = previewSteps(),
                    failure = null,
                    primaryButton = ButtonState(text = stringRes("View your order"), onClick = {}),
                    isOrderLive = true,
                    onBack = {},
                ),
        )
    }
}

@PreviewScreens
@Composable
private fun PreviewHardStop() {
    ZcashTheme {
        PeerCashOutProgressView(
            state =
                PeerCashOutProgressState(
                    title = stringRes("Something went wrong"),
                    subtitle = null,
                    summary = previewSummary,
                    steps =
                        previewSteps(
                            creating = ZappStepStatus.Failed,
                            awaiting = ZappStepStatus.Pending,
                        ),
                    failure =
                        PeerCashOutFailureCard(
                            stepLabel = stringRes("Creating your cash-out order"),
                            reason = stringRes("We are checking on a transaction."),
                            txHash = "0xabcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
                            txExplorerUrl = "https://basescan.org/tx/0xabcdef",
                            retry = null,
                        ),
                    primaryButton = null,
                    onBack = {},
                ),
        )
    }
}
