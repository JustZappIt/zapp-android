package co.electriccoin.zcash.ui.screen.invest.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappRow
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.common.INVEST_GAP_LG
import co.electriccoin.zcash.ui.screen.invest.common.INVEST_GAP_SM
import co.electriccoin.zcash.ui.screen.invest.common.InvestScreenFrame

@Composable
internal fun InvestSettingsView(
    state: InvestSettingsState?,
    onBack: () -> Unit,
) {
    val c = ZappTheme.colors
    InvestScreenFrame(title = stringResource(R.string.invest_settings_title), onBack = onBack) {
        if (state == null) return@InvestScreenFrame
        ZappBorderedCard(padding = 0.dp) {
            ZappRow(
                title = stringResource(R.string.invest_gate_country_label),
                subtitle = state.country.getValue(),
                onClick = state.onCountryClick,
            )
        }
        Spacer(Modifier.height(INVEST_GAP_SM.dp))
        BasicText(
            text = state.status.getValue(),
            style = ZappTheme.typography.caption.copy(color = c.textMuted),
        )
        state.onDemoControlsClick?.let { onClick ->
            Spacer(Modifier.height(INVEST_GAP_LG.dp))
            // Demo builds only, so English only.
            ZappBorderedCard(padding = 0.dp) {
                ZappRow(title = "Demo controls", subtitle = "How the next trade ends, prices, reset", onClick = onClick)
            }
        }
    }
}

@PreviewScreens
@Composable
private fun PreviewSellOnly() {
    ZcashTheme {
        InvestSettingsView(
            InvestSettingsState(
                country = stringRes("Canada"),
                status = stringRes("Invest isn't available in Canada. You can still sell what you hold."),
                onCountryClick = {},
            ),
            onBack = {},
        )
    }
}
