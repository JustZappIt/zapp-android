package co.electriccoin.zcash.ui.screen.invest.buy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappSummaryRow
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.common.INVEST_GAP_LG
import co.electriccoin.zcash.ui.screen.invest.common.INVEST_GAP_MD
import co.electriccoin.zcash.ui.screen.invest.common.INVEST_GAP_SM
import co.electriccoin.zcash.ui.screen.invest.common.InvestModalSheet

/**
 * I6, in ZappConfirmationBottomSheet's frame (same surface, handle and back handling) with the swap review's rows.
 * A null [state] keeps it hidden.
 */
@Composable
internal fun InvestReviewSheet(state: InvestReviewState?) {
    InvestModalSheet(state = state, onDismiss = { it.onDismiss() }) { active ->
        InvestReviewContent(active, Modifier.weight(1f, false))
    }
}

@Composable
private fun InvestReviewContent(
    state: InvestReviewState,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SHEET_PADDING.dp),
        verticalArrangement = Arrangement.spacedBy(INVEST_GAP_MD.dp),
    ) {
        Spacer(Modifier.height(INVEST_GAP_SM.dp))
        BasicText(
            text = stringResource(R.string.invest_review_title),
            style = ZappTheme.typography.sectionTitle.copy(color = c.text, fontWeight = FontWeight.SemiBold),
        )
        ZappBorderedCard(verticalArrangement = Arrangement.spacedBy(INVEST_GAP_SM.dp)) {
            ZappSummaryRow(
                stringResource(R.string.invest_review_from),
                stringResource(R.string.invest_review_from_value),
            )
            ZappSummaryRow(stringResource(R.string.invest_buy_ledger_you_send), state.youSend.getValue())
            ZappSummaryRow(stringResource(R.string.invest_review_at_least), state.atLeast.getValue())
            ZappSummaryRow(
                stringResource(R.string.invest_review_expected),
                state.expected.getValue(),
                valueColor = c.textMuted,
            )
            ZappSummaryRow(stringResource(R.string.invest_buy_ledger_fees), state.fees.getValue())
            ZappSummaryRow(
                stringResource(R.string.invest_review_held_in),
                stringResource(R.string.invest_review_held_in_value),
            )
            ZappSummaryRow(
                stringResource(R.string.invest_review_price_held),
                state.countdown.getValue(),
                valueColor = if (state.isExpired) c.danger else c.text,
            )
        }
        BasicText(text = state.privacy.getValue(), style = ZappTheme.typography.caption.copy(color = c.textMuted))
        state.errorText?.let {
            BasicText(
                text = it.getValue(),
                style = ZappTheme.typography.caption.copy(color = c.danger, fontWeight = FontWeight.Medium),
            )
        }
        ZappButton(
            text = state.primaryButton.text.getValue(),
            enabled = state.primaryButton.isEnabled,
            loading = state.isBusy,
            variant = ZappButtonVariant.Primary,
            modifier = Modifier.fillMaxWidth(),
            onClick = state.primaryButton.onClick,
        )
        Spacer(Modifier.height(INVEST_GAP_LG.dp))
    }
}

private const val SHEET_PADDING = 24

@PreviewScreens
@Composable
private fun PreviewReview() {
    ZcashTheme {
        InvestReviewContent(
            InvestReviewState(
                youSend = stringRes("0.06478 ZEC"),
                atLeast = stringRes("$98.00 · 0.4366 NVDA"),
                expected = stringRes("$99.01 · 0.4410 NVDA"),
                fees = stringRes("$0.95"),
                countdown = stringRes("9:42"),
                isExpired = false,
                privacy = stringRes("Only you and NEAR Intents can see this holding."),
                primaryButton = ButtonState(stringRes("Confirm and buy")),
                isBusy = false,
                errorText = null,
                onDismiss = {},
            ),
        )
    }
}
