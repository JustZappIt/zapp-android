package co.electriccoin.zcash.ui.screen.advancedsettings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappRow
import co.electriccoin.zcash.ui.design.component.zapp.ZappRowDivider
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ProvideZappTheme
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes

@Composable
fun AdvancedSettings(state: AdvancedSettingsState) {
    val c = ZappTheme.colors
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(
                    WindowInsets.statusBars.union(WindowInsets.displayCutout)
                ),
    ) {
        ZappScreenHeader(title = stringResource(R.string.advanced_settings_title))

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))

            // Items card
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .background(c.surface, RectangleShape)
                        .border(BorderStroke(1.dp, c.border), RectangleShape),
            ) {
                state.items.forEachIndexed { index, item ->
                    ZappRow(
                        title = item.title.getValue(),
                        icon = item.icon,
                        onClick = if (item.isEnabled) item.onClick else null,
                        modifier = Modifier.alpha(if (item.isEnabled) 1f else 0.45f),
                    )
                    if (index != state.items.lastIndex) {
                        ZappRowDivider(inset = true)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Info hint
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = c.textSubtle,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(8.dp))
                BasicText(
                    text = stringResource(R.string.advanced_settings_info),
                    style = ZappTheme.typography.rowSubtitle.copy(color = c.textMuted),
                )
            }

            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(20.dp))

            // Delete wallet
            ZappButton(
                text = stringResource(R.string.advanced_settings_delete_button),
                variant = ZappButtonVariant.Danger,
                onClick = state.onDeleteWallet,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp),
            )

            Spacer(Modifier.height(20.dp))
        }

        ZappBottomActionBar(onBack = state.onBack)
    }
}

@PreviewScreens
@Composable
private fun AdvancedSettingsPreview() =
    ProvideZappTheme {
        AdvancedSettings(
            state =
                AdvancedSettingsState(
                    onBack = {},
                    items =
                        listOf(
                            AdvancedSettingsItem(
                                title = stringRes("Recovery phrase"),
                                icon = Icons.Default.Key,
                                onClick = {},
                            ),
                            AdvancedSettingsItem(
                                title = stringRes("Export data"),
                                icon = Icons.Default.FileDownload,
                                onClick = {},
                            ),
                            AdvancedSettingsItem(
                                title = stringRes("Tax export"),
                                icon = Icons.Default.Receipt,
                                isEnabled = false,
                                onClick = {},
                            ),
                        ),
                    onDeleteWallet = {},
                ),
        )
    }
