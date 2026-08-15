package co.electriccoin.zcash.ui.screen.advancedsettings

import androidx.compose.ui.graphics.vector.ImageVector
import co.electriccoin.zcash.ui.design.util.StringResource

data class AdvancedSettingsState(
    val onBack: () -> Unit,
    val items: List<AdvancedSettingsItem>,
    val onDeleteWallet: () -> Unit,
)

data class AdvancedSettingsItem(
    val title: StringResource,
    val icon: ImageVector,
    val isEnabled: Boolean = true,
    val onClick: () -> Unit,
)
