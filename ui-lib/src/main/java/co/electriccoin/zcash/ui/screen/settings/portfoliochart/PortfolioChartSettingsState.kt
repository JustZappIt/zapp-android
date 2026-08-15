package co.electriccoin.zcash.ui.screen.settings.portfoliochart

import co.electriccoin.zcash.ui.design.component.ButtonState

data class PortfolioChartSettingsState(
    val isEnabled: Boolean,
    val saveButton: ButtonState,
    val onEnabledChange: (Boolean) -> Unit,
    val onBack: () -> Unit,
)
