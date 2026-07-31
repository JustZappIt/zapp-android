package co.electriccoin.zcash.ui.screen.settings.p2p

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappBackButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes

@Composable
internal fun P2pTransactionsView(state: P2pTransactionsState) {
    val c = ZappTheme.colors
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ZappScreenHeader(title = stringResource(R.string.p2p_transactions_title))

            LazyColumn(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                contentPadding =
                    PaddingValues(
                        start = HORIZONTAL_PADDING.dp,
                        end = HORIZONTAL_PADDING.dp,
                        top = SECTION_GAP.dp,
                        bottom = BACK_DOCK_CLEARANCE.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(GAP_MD.dp),
            ) {
                item {
                    BalanceCard(
                        state = state.balance,
                        refund = state.refund,
                        isRefreshing = state.isRefreshing,
                    )
                }

                state.errorMessage?.let { msg ->
                    item {
                        BasicText(
                            text = msg.getValue(),
                            style = ZappTheme.typography.body.copy(color = c.danger),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                state.emptyMessage?.let { msg ->
                    item {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = EMPTY_PADDING_V.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(
                                text = msg.getValue(),
                                style = ZappTheme.typography.body.copy(color = c.textMuted),
                            )
                        }
                    }
                }

                items(state.rows, key = { it.orderId }) { row -> TransactionCard(row) }
            }
        }

        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = HORIZONTAL_PADDING.dp, bottom = GAP_MD.dp)
                    .background(c.surface, RectangleShape)
                    .border(BorderStroke(1.dp, c.accent), RectangleShape),
        ) {
            ZappBackButton(onClick = state.onBack)
        }
    }
    state.confirmRefund?.let { RefundConfirmDialog(dialog = it) }
}

@Composable
private fun BalanceCard(state: BalanceState, refund: RefundUiState, isRefreshing: Boolean) {
    val c = ZappTheme.colors
    ZappBorderedCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                text = stringResource(R.string.p2p_transactions_balance_label),
                style = ZappTheme.typography.eyebrow.copy(color = c.textMuted),
                modifier = Modifier.weight(1f),
            )
            if (isRefreshing) {
                CircularProgressIndicator(
                    color = c.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(SPINNER_SIZE.dp),
                )
            }
        }
        Spacer(Modifier.height(GAP_SM.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                when (state) {
                    BalanceState.Loading -> {
                        BasicText(
                            text = stringResource(R.string.p2p_transactions_balance_loading),
                            style = ZappTheme.typography.display.copy(color = c.text, fontWeight = FontWeight.SemiBold),
                        )
                    }

                    BalanceState.Unavailable -> {
                        BasicText(
                            text = stringResource(R.string.p2p_transactions_balance_unavailable),
                            style = ZappTheme.typography.body.copy(color = c.danger),
                        )
                    }

                    is BalanceState.Loaded -> {
                        BasicText(
                            text = state.balanceUsdc.getValue(),
                            style = ZappTheme.typography.display.copy(color = c.text, fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
            }
            RefundControl(refund)
        }
        (refund as? RefundUiState.FailedRetry)?.let {
            Spacer(Modifier.height(GAP_SM.dp))
            BasicText(
                text = it.message.getValue(),
                style = ZappTheme.typography.caption.copy(color = c.danger),
            )
        }
        if (state is BalanceState.Loaded) {
            Spacer(Modifier.height(GAP_SM.dp))
            AccountAddressRow(addressShort = state.accountAddressShort, explorerUrl = state.accountExplorerUrl)
        }
    }
}

@Composable
private fun RefundControl(refund: RefundUiState) {
    val c = ZappTheme.colors
    when (refund) {
        RefundUiState.Hidden -> {
            Unit
        }

        is RefundUiState.Available -> {
            Spacer(Modifier.width(GAP_MD.dp))
            CompactPrimaryButton(
                text = stringResource(R.string.p2p_transactions_refund_button),
                onClick = refund.onClick,
            )
        }

        RefundUiState.InProgress -> {
            Spacer(Modifier.width(GAP_MD.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = c.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(SPINNER_SIZE.dp),
                )
                Spacer(Modifier.width(GAP_SM.dp))
                BasicText(
                    text = stringResource(R.string.p2p_transactions_refund_in_progress),
                    style = ZappTheme.typography.caption.copy(color = c.textMuted),
                )
            }
        }

        is RefundUiState.FailedRetry -> {
            Spacer(Modifier.width(GAP_MD.dp))
            CompactPrimaryButton(
                text = stringResource(R.string.p2p_transactions_refund_retry),
                onClick = refund.onRetry,
            )
        }
    }
}

/**
 * Inline yellow CTA sized for sitting next to a balance display — about half the height of
 * the standard [ZappButton] and uses the [ZappTextStyles.buttonSmall] type ramp. Keep visually
 * distinct (still accent-coloured) but unobtrusive against the balance number.
 */
@Composable
private fun CompactPrimaryButton(text: String, onClick: () -> Unit) {
    val c = ZappTheme.colors
    Box(
        modifier =
            Modifier
                .background(c.accent, RectangleShape)
                .clickable(onClick = onClick)
                .padding(horizontal = COMPACT_BUTTON_PADDING_H.dp, vertical = COMPACT_BUTTON_PADDING_V.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = ZappTheme.typography.buttonSmall.copy(color = c.onAccent),
        )
    }
}

@Composable
private fun RefundConfirmDialog(dialog: ConfirmRefundDialog) {
    val c = ZappTheme.colors
    AlertDialog(
        onDismissRequest = dialog.onDismiss,
        containerColor = c.surface,
        titleContentColor = c.text,
        textContentColor = c.textMuted,
        shape = RectangleShape,
        title = {
            BasicText(
                text = stringResource(R.string.p2p_transactions_refund_dialog_title),
                style = ZappTheme.typography.sectionTitle.copy(color = c.text),
            )
        },
        text = {
            BasicText(
                text = stringResource(R.string.p2p_transactions_refund_dialog_message, dialog.amount.getValue()),
                style = ZappTheme.typography.body.copy(color = c.textMuted),
            )
        },
        confirmButton = {
            DialogTextButton(
                label = stringResource(R.string.p2p_transactions_refund_dialog_confirm),
                color = c.accentText,
                onClick = dialog.onConfirm,
            )
        },
        dismissButton = {
            DialogTextButton(
                label = stringResource(R.string.p2p_transactions_refund_dialog_cancel),
                color = c.textMuted,
                onClick = dialog.onDismiss,
            )
        },
    )
}

@Composable
private fun DialogTextButton(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        modifier =
            Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = DIALOG_BUTTON_PADDING.dp, vertical = DIALOG_BUTTON_PADDING_V.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style = ZappTheme.typography.button.copy(color = color, fontWeight = FontWeight.Medium),
        )
    }
}

@Composable
private fun AccountAddressRow(addressShort: String, explorerUrl: String?) {
    val c = ZappTheme.colors
    val uriHandler = LocalUriHandler.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            text = stringResource(R.string.p2p_transactions_account_label),
            style = ZappTheme.typography.chip.copy(color = c.textSubtle, fontWeight = FontWeight.Normal),
        )
        Spacer(Modifier.weight(1f))
        BasicText(
            text = addressShort,
            style =
                ZappTheme.typography.chip.copy(
                    color = if (explorerUrl != null) c.accent else c.text,
                ),
            modifier =
                if (explorerUrl != null) {
                    Modifier.clickable { uriHandler.openUri(explorerUrl) }
                } else {
                    Modifier
                },
        )
    }
}

@Composable
private fun TransactionCard(row: P2pTransactionRow) {
    val c = ZappTheme.colors
    var expanded by remember(row.orderId) { mutableStateOf(false) }
    val canExpand = row.detail != null

    ZappBorderedCard(
        verticalArrangement = Arrangement.spacedBy(GAP_SM.dp),
        modifier = if (canExpand) Modifier.clickable { expanded = !expanded } else Modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                text = row.typeLabel.getValue(),
                style = ZappTheme.typography.eyebrow.copy(color = c.textMuted),
                modifier = Modifier.weight(1f),
            )
            StatusPill(label = row.statusLabel.getValue(), tone = row.statusTone)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            BasicText(
                text = row.amountUsdc.getValue(),
                style = ZappTheme.typography.sectionTitle.copy(color = c.text, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            BasicText(
                text = row.amountFiat.getValue(),
                style = ZappTheme.typography.body.copy(color = c.textMuted),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                text = stringResource(R.string.p2p_transactions_row_order_id, row.orderId),
                style = ZappTheme.typography.caption.copy(color = c.textSubtle),
                modifier = Modifier.weight(1f),
            )
            row.timestamp?.let {
                BasicText(text = it.getValue(), style = ZappTheme.typography.caption.copy(color = c.textSubtle))
            }
            if (canExpand) {
                Spacer(Modifier.width(GAP_SM.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription =
                        stringResource(
                            if (expanded) {
                                R.string.p2p_transactions_detail_collapse_content_description
                            } else {
                                R.string.p2p_transactions_detail_expand_content_description
                            },
                        ),
                    tint = c.textSubtle,
                    modifier = Modifier.size(CHEVRON_SIZE.dp),
                )
            }
        }

        if (expanded && row.detail != null) {
            TransactionDetailPanel(row.detail)
        }
    }
}

@Composable
private fun TransactionDetailPanel(detail: TransactionDetail) {
    val c = ZappTheme.colors
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(GAP_SM.dp)) {
        Spacer(Modifier.height(GAP_SM.dp))
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(c.border),
        )

        detail.fee?.let {
            DetailRow(
                label = stringResource(R.string.p2p_transactions_detail_fee),
                value = it.getValue(),
            )
        }
        if (detail.paidByUpiPlain != null) {
            DetailRow(
                label = stringResource(R.string.p2p_transactions_detail_paid_by),
                value = detail.paidByUpiPlain,
            )
        }
        detail.paidToUpiPlain?.let { vpa ->
            DetailRow(
                label = stringResource(R.string.p2p_transactions_detail_paid_to),
                value = vpa,
            )
        }
        if (detail.merchantAddressShort != null) {
            DetailRow(
                label = stringResource(R.string.p2p_transactions_detail_p2p_merchant_address),
                value = detail.merchantAddressShort,
                onValueClick = detail.merchantExplorerUrl?.let { url -> { uriHandler.openUri(url) } },
            )
        }
        detail.duration?.let {
            DetailRow(label = stringResource(R.string.p2p_transactions_detail_duration), value = it.getValue())
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, onValueClick: (() -> Unit)? = null) {
    val c = ZappTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            text = label,
            style = ZappTheme.typography.caption.copy(color = c.textSubtle),
            modifier = Modifier.weight(1f),
        )
        BasicText(
            text = value,
            style =
                ZappTheme.typography.caption.copy(
                    color = if (onValueClick != null) c.accent else c.text,
                    fontWeight = FontWeight.Medium,
                ),
            modifier = if (onValueClick != null) Modifier.clickable(onClick = onValueClick) else Modifier,
        )
    }
}

@Composable
private fun StatusPill(label: String, tone: P2pTransactionRow.StatusTone) {
    val c = ZappTheme.colors
    val (fg, bg) =
        when (tone) {
            P2pTransactionRow.StatusTone.Success -> c.accentText to c.accentSoft
            P2pTransactionRow.StatusTone.Pending -> c.text to c.surfaceAlt
            P2pTransactionRow.StatusTone.Cancelled -> c.textMuted to c.surfaceAlt
            P2pTransactionRow.StatusTone.Failed -> c.danger to c.surfaceAlt
        }
    Box(
        modifier =
            Modifier
                .background(bg, RectangleShape)
                .border(BorderStroke(1.dp, c.border), RectangleShape)
                .padding(horizontal = PILL_PADDING_H.dp, vertical = PILL_PADDING_V.dp),
    ) {
        BasicText(
            text = label,
            style = ZappTheme.typography.caption.copy(color = fg, fontWeight = FontWeight.Medium),
        )
    }
}

private const val HORIZONTAL_PADDING = 14
private const val SECTION_GAP = 16
private const val BACK_DOCK_CLEARANCE = 72
private const val GAP_SM = 6
private const val GAP_MD = 12
private const val SPINNER_SIZE = 18
private const val PILL_PADDING_H = 10
private const val PILL_PADDING_V = 4
private const val EMPTY_PADDING_V = 32
private const val DIALOG_BUTTON_PADDING = 16
private const val DIALOG_BUTTON_PADDING_V = 12
private const val COMPACT_BUTTON_PADDING_H = 12
private const val COMPACT_BUTTON_PADDING_V = 6
private const val CHEVRON_SIZE = 18

@PreviewScreens
@Composable
private fun PreviewLoaded() =
    ZcashTheme {
        P2pTransactionsView(
            state =
                P2pTransactionsState(
                    onBack = {},
                    onRefresh = {},
                    isRefreshing = false,
                    balance =
                        BalanceState.Loaded(
                            balanceUsdc = stringRes("4.5 USDC on Base"),
                            accountAddressShort = "0x3a28…558a",
                            accountExplorerUrl = null,
                        ),
                    refund = RefundUiState.Available(onClick = {}),
                    confirmRefund = null,
                    rows =
                        listOf(
                            P2pTransactionRow(
                                orderId = "547444",
                                typeLabel = stringRes("Pay"),
                                statusLabel = stringRes("Completed"),
                                statusTone = P2pTransactionRow.StatusTone.Success,
                                amountUsdc = stringRes("0.4 USDC"),
                                amountFiat = stringRes("37.28 INR"),
                                timestamp = stringRes("23 May 2026, 14:21"),
                                explorerUrl = null,
                                detail =
                                    TransactionDetail(
                                        fee = stringRes("0.050 USDC"),
                                        paidByUpiPlain = "merchant@okhdfc",
                                        paidToUpiPlain = "friend@ybl",
                                        merchantAddressShort = "0xa8e6…fab2",
                                        merchantExplorerUrl = null,
                                        duration = stringRes("1m 32s"),
                                    ),
                            ),
                        ),
                    emptyMessage = null,
                    errorMessage = null,
                ),
        )
    }
