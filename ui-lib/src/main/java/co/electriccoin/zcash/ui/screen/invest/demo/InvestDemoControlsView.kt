package co.electriccoin.zcash.ui.screen.invest.demo

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.common.invest.demo.InvestDemoOutcome
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappRowDivider
import co.electriccoin.zcash.ui.design.component.zapp.ZappSectionLabel
import co.electriccoin.zcash.ui.design.component.zapp.ZappSelectionRow
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.screen.invest.common.INVEST_GAP_LG
import co.electriccoin.zcash.ui.screen.invest.common.INVEST_GAP_SM
import co.electriccoin.zcash.ui.screen.invest.common.InvestNotice
import co.electriccoin.zcash.ui.screen.invest.common.InvestScreenFrame

// A debug-only tool, so its copy is English only and stays out of the translated strings.
@Composable
internal fun InvestDemoControlsView(state: InvestDemoControlsState) {
    val c = ZappTheme.colors
    InvestScreenFrame(title = "Demo controls", onBack = state.onBack) {
        InvestNotice(
            body =
                "This build runs Invest on a demo engine. Prices, trades and holdings are simulated on this " +
                    "phone: nothing is bought or sold, no ZEC is sent, and holdings clear when the app closes.",
        )
        Spacer(Modifier.height(INVEST_GAP_LG.dp))
        ZappSectionLabel(text = "Next buy or sale ends")
        OUTCOMES.forEach { (outcome, title, subtitle) ->
            ZappSelectionRow(
                title = title,
                subtitle = subtitle,
                isSelected = state.outcome == outcome,
                onClick = { state.onOutcome(outcome) },
            )
            ZappRowDivider()
        }
        Spacer(Modifier.height(INVEST_GAP_LG.dp))
        ZappSectionLabel(text = "Prices")
        ZappSelectionRow(
            title = "Quotes normally",
            subtitle = null,
            isSelected = state.hasLiquidity,
            onClick = { state.onLiquidity(true) },
        )
        ZappRowDivider()
        ZappSelectionRow(
            title = "No liquidity",
            subtitle = "Every quote says no price, as on a quiet weekend",
            isSelected = !state.hasLiquidity,
            onClick = { state.onLiquidity(false) },
        )
        Spacer(Modifier.height(INVEST_GAP_LG.dp))
        ZappButton(
            text = "Clear demo holdings and trades",
            variant = ZappButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth(),
            onClick = state.onReset,
        )
        if (state.isReset) {
            Spacer(Modifier.height(INVEST_GAP_SM.dp))
            BasicText(
                text = "Cleared. Your country and setup are kept.",
                style = ZappTheme.typography.caption.copy(color = c.textMuted),
            )
        }
    }
}

private val OUTCOMES =
    listOf(
        Triple(InvestDemoOutcome.COMPLETES, "Completes", "The stock is held, or the sale's ZEC is sent"),
        Triple(InvestDemoOutcome.REFUNDED, "Refunded", "The ZEC comes back, or the stock returns"),
        Triple(InvestDemoOutcome.NEEDS_ATTENTION, "Needs attention", "Stuck until dismissed, with support"),
    )

@PreviewScreens
@Composable
private fun PreviewDemoControls() {
    ZcashTheme {
        InvestDemoControlsView(
            InvestDemoControlsState(
                outcome = InvestDemoOutcome.COMPLETES,
                hasLiquidity = true,
                isReset = false,
                onOutcome = {},
                onLiquidity = {},
                onReset = {},
                onBack = {},
            ),
        )
    }
}
