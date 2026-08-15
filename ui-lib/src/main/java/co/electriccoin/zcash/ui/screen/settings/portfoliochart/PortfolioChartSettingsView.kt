package co.electriccoin.zcash.ui.screen.settings.portfoliochart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappRow
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettingsGroup
import co.electriccoin.zcash.ui.design.component.zapp.ZappToggle
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ProvideZappTheme
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes

@Composable
internal fun PortfolioChartSettingsView(
    state: PortfolioChartSettingsState,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
    ) {
        ZappScreenHeader(title = stringResource(R.string.settings_portfolio_chart_title))

        Column(modifier = Modifier.weight(1f)) {
            ZappSettingsGroup(
                title = stringResource(R.string.settings_portfolio_chart_section),
                footer = stringResource(R.string.settings_portfolio_chart_footer),
            ) {
                ZappRow(
                    title = stringResource(R.string.settings_portfolio_chart_toggle_title),
                    subtitle = stringResource(R.string.settings_portfolio_chart_toggle_subtitle),
                    icon = Icons.AutoMirrored.Filled.ShowChart,
                    iconTint = c.accentText,
                    iconBackground = c.accentSoft,
                    trailing = {
                        ZappToggle(
                            checked = state.isEnabled,
                            onClick = { state.onEnabledChange(!state.isEnabled) },
                        )
                    },
                    onClick = { state.onEnabledChange(!state.isEnabled) },
                )
            }

            Spacer(Modifier.height(ZappTheme.spacing.xl))
        }

        ZappBottomActionBar(
            onBack = state.onBack,
            primaryAction = {
                ZappButton(
                    text = state.saveButton.text.getValue(),
                    onClick = state.saveButton.onClick,
                    enabled = state.saveButton.isEnabled,
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                )
            },
        )
    }
}

@PreviewScreens
@Composable
private fun PortfolioChartSettingsPreview() =
    ProvideZappTheme {
        PortfolioChartSettingsView(
            state =
                PortfolioChartSettingsState(
                    isEnabled = true,
                    saveButton =
                        ButtonState(
                            text = stringRes(R.string.settings_portfolio_chart_save),
                        ),
                    onEnabledChange = {},
                    onBack = {},
                )
        )
    }
