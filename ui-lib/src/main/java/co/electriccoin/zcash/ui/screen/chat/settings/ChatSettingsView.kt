// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappGroupHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappRow
import co.electriccoin.zcash.ui.design.component.zapp.ZappRowDivider
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettingsGroup
import co.electriccoin.zcash.ui.design.component.zapp.ZappToggle
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.screen.chat.common.rememberNotificationPermissionRequester

@Composable
internal fun ChatSettingsView(
    state: ChatSettingsState,
    modifier: Modifier = Modifier,
) {
    var staged by remember(state.preferences) { mutableStateOf(state.preferences) }
    val c = ZappTheme.colors
    val requestNotificationsPermission =
        rememberNotificationPermissionRequester { granted ->
            state.onSaveClick(staged.copy(isBackgroundDeliveryEnabled = granted))
        }
    val onSave = {
        if (staged.isBackgroundDeliveryEnabled && !state.preferences.isBackgroundDeliveryEnabled) {
            requestNotificationsPermission()
        } else {
            state.onSaveClick(staged)
        }
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
    ) {
        ZappScreenHeader(title = stringResource(R.string.chat_settings_title))

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
        ) {
            ZappSettingsGroup(
                title = stringResource(R.string.chat_settings_section_privacy),
                footer = stringResource(R.string.chat_settings_privacy_footer),
            ) {
                ToggleRow(
                    title = stringResource(R.string.chat_settings_read_receipts_toggle_title),
                    subtitle = stringResource(R.string.chat_settings_read_receipts_toggle_subtitle),
                    icon = Icons.Default.Check,
                    checked = staged.isReadReceiptsEnabled,
                    onToggle = { staged = staged.copy(isReadReceiptsEnabled = it) },
                )
                ZappRowDivider(inset = true)
                ToggleRow(
                    title = stringResource(R.string.chat_settings_online_status_toggle_title),
                    subtitle = stringResource(R.string.chat_settings_online_status_toggle_subtitle),
                    icon = Icons.Default.Person,
                    checked = staged.isOnlineStatusEnabled,
                    onToggle = { staged = staged.copy(isOnlineStatusEnabled = it) },
                )
            }

            ZappSettingsGroup(
                title = stringResource(R.string.chat_settings_section_delivery),
                footer = stringResource(R.string.chat_settings_delivery_footer),
            ) {
                ToggleRow(
                    title = stringResource(R.string.chat_settings_background_push_toggle_title),
                    subtitle = stringResource(R.string.chat_settings_background_push_toggle_subtitle),
                    icon = Icons.Default.Notifications,
                    checked = staged.isBackgroundDeliveryEnabled,
                    onToggle = { staged = staged.copy(isBackgroundDeliveryEnabled = it) },
                )
            }

            Spacer(Modifier.height(16.dp))
        }

        ZappBottomActionBar(
            onBack = state.onBack,
            primaryAction = {
                ZappButton(
                    text = stringResource(R.string.chat_settings_save),
                    onClick = onSave,
                    enabled = staged != state.preferences,
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                )
            },
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val c = ZappTheme.colors
    ZappRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconTint = c.accentText,
        iconBackground = c.accentSoft,
        trailing = {
            ZappToggle(checked = checked, onClick = { onToggle(!checked) })
        },
        onClick = { onToggle(!checked) },
    )
}

@PreviewScreens
@Composable
private fun ChatSettingsPreview() =
    ZcashTheme {
        ChatSettingsView(
            state =
                ChatSettingsState(
                    preferences =
                        ChatPreferences(
                            isReadReceiptsEnabled = true,
                            isOnlineStatusEnabled = true,
                            isBackgroundDeliveryEnabled = false,
                        ),
                    onSaveClick = {},
                    onBack = {},
                ),
        )
    }
