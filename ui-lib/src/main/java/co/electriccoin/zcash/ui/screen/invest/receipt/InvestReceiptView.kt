package co.electriccoin.zcash.ui.screen.invest.receipt

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappCopyableAddress
import co.electriccoin.zcash.ui.design.component.zapp.ZappSectionLabel
import co.electriccoin.zcash.ui.design.component.zapp.ZappSummaryRow
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.orHiddenString
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.common.INVEST_GAP_LG
import co.electriccoin.zcash.ui.screen.invest.common.INVEST_GAP_SM
import co.electriccoin.zcash.ui.screen.invest.common.InvestScreenFrame

@Composable
internal fun InvestReceiptView(state: InvestReceiptState) {
    val c = ZappTheme.colors
    val hidden = stringRes(co.electriccoin.zcash.ui.design.R.string.hide_balance_placeholder)
    InvestScreenFrame(title = state.title.getValue(), onBack = state.onBack, primaryButton = state.progressButton) {
        state.value?.let {
            BasicText(text = it orHiddenString hidden, style = ZappTheme.typography.display.copy(color = c.text))
        }
        state.units?.let {
            Spacer(Modifier.height(4.dp))
            BasicText(text = it orHiddenString hidden, style = ZappTheme.typography.body.copy(color = c.textMuted))
        }
        Spacer(Modifier.height(INVEST_GAP_LG.dp))
        ZappBorderedCard(verticalArrangement = Arrangement.spacedBy(INVEST_GAP_SM.dp)) {
            ZappSummaryRow(
                label = stringResource(R.string.invest_receipt_status),
                value = state.status.getValue(),
                valueColor = if (state.isStatusDanger) c.danger else c.text,
            )
            state.fees?.let {
                ZappSummaryRow(stringResource(R.string.invest_buy_ledger_fees), it.getValue())
            }
            state.date?.let {
                ZappSummaryRow(stringResource(R.string.invest_receipt_date), it.getValue())
            }
            ZappSummaryRow(
                stringResource(R.string.invest_review_held_in),
                stringResource(R.string.invest_review_held_in_value),
            )
        }
        Spacer(Modifier.height(INVEST_GAP_LG.dp))
        SupportSection(state)
    }
}

/** Collapsed by default: the reference support needs, with a copy button. */
@Composable
private fun SupportSection(state: InvestReceiptState) {
    val c = ZappTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = state.onToggleSupport)
                .semantics { role = Role.Button }
                .padding(vertical = INVEST_GAP_SM.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZappSectionLabel(text = stringResource(R.string.invest_receipt_for_support), modifier = Modifier.weight(1f))
        Icon(
            imageVector = if (state.isSupportOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = c.textMuted,
            modifier = Modifier.size(CHEVRON_SIZE.dp),
        )
    }
    if (state.isSupportOpen) {
        Spacer(Modifier.height(INVEST_GAP_SM.dp))
        ZappCopyableAddress(
            label = stringResource(R.string.invest_receipt_reference),
            address = state.reference,
            copyContentDescription = stringResource(R.string.invest_receipt_copy),
            onCopy = state.onCopyReference,
        )
    }
}

private const val CHEVRON_SIZE = 20

@PreviewScreens
@Composable
private fun PreviewReceipt() {
    ZcashTheme {
        InvestReceiptView(
            InvestReceiptState(
                title = stringRes("Bought NVIDIA"),
                value = stringRes("$99.01"),
                units = stringRes("0.4410 NVDA"),
                status = stringRes("Held privately"),
                isStatusDanger = false,
                fees = stringRes("$0.95"),
                date = stringRes("26 Sep 2026, 12:31"),
                reference = "t1Rv4exT7bqhZqi2j7xz8bUHDMxwosrjADU",
                isSupportOpen = true,
                onToggleSupport = {},
                onCopyReference = {},
                progressButton = ButtonState(stringRes("See progress")),
                onBack = {},
            ),
        )
    }
}
