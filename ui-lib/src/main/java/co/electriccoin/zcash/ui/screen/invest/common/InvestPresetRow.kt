package co.electriccoin.zcash.ui.screen.invest.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue

@Composable
internal fun InvestPresetRow(presets: List<InvestPresetState>) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZappTheme.spacing.md), modifier = Modifier.fillMaxWidth()) {
        presets.forEach { preset ->
            ZappButton(
                text = preset.label.getValue(),
                variant = ZappButtonVariant.Secondary,
                enabled = preset.isEnabled,
                modifier = Modifier.weight(1f),
                onClick = preset.onClick,
            )
        }
    }
}

internal data class InvestPresetState(
    val label: StringResource,
    val isEnabled: Boolean,
    val onClick: () -> Unit,
)
