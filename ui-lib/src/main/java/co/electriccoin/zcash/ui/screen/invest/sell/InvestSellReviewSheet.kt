package co.electriccoin.zcash.ui.screen.invest.sell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
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

/** I9: what the private-account key is about to sign, in words, with the raw message one tap away. */
@Composable
internal fun InvestSellReviewSheet(state: InvestSellReviewState?) {
    InvestModalSheet(state = state, onDismiss = { it.onDismiss() }) { active ->
        InvestSellReviewContent(active, Modifier.weight(1f, false))
    }
}

@Composable
private fun InvestSellReviewContent(
    state: InvestSellReviewState,
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
            text = stringResource(R.string.invest_sell_review_title),
            style = ZappTheme.typography.sectionTitle.copy(color = c.text, fontWeight = FontWeight.SemiBold),
        )
        BasicText(text = state.authorisation.getValue(), style = ZappTheme.typography.body.copy(color = c.text))
        ZappBorderedCard(verticalArrangement = Arrangement.spacedBy(INVEST_GAP_SM.dp)) {
            ZappSummaryRow(stringResource(R.string.invest_review_at_least), state.atLeast.getValue())
            ZappSummaryRow(
                stringResource(R.string.invest_review_expected),
                state.expected.getValue(),
                valueColor = c.textMuted,
            )
            ZappSummaryRow(stringResource(R.string.invest_sell_ledger_fees), state.fees.getValue())
            ZappSummaryRow(
                stringResource(R.string.invest_sell_ledger_paid_to),
                stringResource(R.string.invest_sell_ledger_paid_to_value),
            )
            ZappSummaryRow(
                stringResource(R.string.invest_sell_valid_for),
                state.countdown.getValue(),
                valueColor = if (state.isExpired) c.danger else c.text,
            )
        }
        SignedMessage(state)
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

/** "View signed message": the exact payload, in mono, for the curious and for support. Collapsed by default. */
@Composable
private fun SignedMessage(state: InvestSellReviewState) {
    val c = ZappTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = state.onToggleSignedMessage)
                .semantics { role = Role.Button }
                .padding(vertical = INVEST_GAP_SM.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = stringResource(R.string.invest_sell_view_signed_message),
            style = ZappTheme.typography.caption.copy(color = c.accentText, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector =
                if (state.isSignedMessageOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = c.textMuted,
            modifier = Modifier.size(CHEVRON_SIZE.dp),
        )
    }
    if (state.isSignedMessageOpen) {
        ZappBorderedCard {
            BasicText(text = state.signedMessage, style = ZappTheme.typography.mono.copy(color = c.textMuted))
        }
    }
}

private const val SHEET_PADDING = 24
private const val CHEVRON_SIZE = 20

@PreviewScreens
@Composable
private fun PreviewSellReview() {
    ZcashTheme {
        InvestSellReviewContent(
            InvestSellReviewState(
                authorisation =
                    stringRes(
                        "Move 0.4410 NVDA (about $98.99) from your private account to NEAR Intents, to be sold " +
                            "for ZEC and sent to your wallet. Nothing else.",
                    ),
                atLeast = stringRes("0.0628 ZEC"),
                expected = stringRes("0.0634 ZEC"),
                fees = stringRes("$1.12"),
                countdown = stringRes("9:12"),
                isExpired = false,
                signedMessage = "{\"signer_id\":\"…\",\"intents\":[{\"intent\":\"transfer\"}]}",
                isSignedMessageOpen = true,
                onToggleSignedMessage = {},
                primaryButton = ButtonState(stringRes("Confirm and sell")),
                isBusy = false,
                errorText = null,
                onDismiss = {},
            ),
        )
    }
}
