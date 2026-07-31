// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.backgrounddelivery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappGroupHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappRow
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappToggle
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.screen.chat.common.rememberNotificationPermissionRequester

@Composable
internal fun BackgroundDeliverySettingsView(state: BackgroundDeliverySettingsState) {
    var isEnabledSelected by remember(state.isEnabled) { mutableStateOf(state.isEnabled) }
    val isSaveEnabled = isEnabledSelected != state.isEnabled
    val c = ZappTheme.colors
    val requestNotificationsPermission =
        rememberNotificationPermissionRequester { granted ->
            if (granted) state.onSaveClick(true)
        }
    val onSave = {
        if (isEnabledSelected) {
            requestNotificationsPermission()
        } else {
            state.onSaveClick(false)
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
    ) {
        ZappScreenHeader(title = stringResource(R.string.background_delivery_settings_title))

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(64.dp)
                        .background(c.accentSoft, RectangleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = null,
                    tint = c.accentText,
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            BasicText(
                text = stringResource(R.string.background_delivery_settings_intro),
                style = ZappTheme.typography.body.copy(color = c.textMuted),
            )
            Spacer(Modifier.height(24.dp))

            InfoBlock(
                title = stringResource(R.string.background_delivery_settings_delivery_title),
                body = stringResource(R.string.background_delivery_settings_delivery_body),
            )
            InfoBlock(
                title = stringResource(R.string.background_delivery_settings_battery_title),
                body = stringResource(R.string.background_delivery_settings_battery_body),
            )
            InfoBlock(
                title = stringResource(R.string.background_delivery_settings_privacy_title),
                body = stringResource(R.string.background_delivery_settings_privacy_body),
            )

            ZappGroupHeader(text = stringResource(R.string.background_delivery_settings_section_control))
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(c.surface, RectangleShape)
                        .border(BorderStroke(1.dp, c.border), RectangleShape),
            ) {
                ZappRow(
                    title = stringResource(R.string.background_delivery_settings_toggle_title),
                    subtitle = stringResource(R.string.background_delivery_settings_toggle_subtitle),
                    icon = Icons.Default.Notifications,
                    iconTint = c.accentText,
                    iconBackground = c.accentSoft,
                    trailing = {
                        ZappToggle(
                            checked = isEnabledSelected,
                            onClick = { isEnabledSelected = !isEnabledSelected },
                        )
                    },
                    onClick = { isEnabledSelected = !isEnabledSelected },
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        ZappBottomActionBar(
            onBack = state.onBack,
            primaryAction = {
                ZappButton(
                    text = stringResource(R.string.background_delivery_settings_save),
                    onClick = onSave,
                    enabled = isSaveEnabled,
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
private fun InfoBlock(
    title: String,
    body: String,
) {
    val c = ZappTheme.colors
    Column(modifier = Modifier.padding(bottom = 18.dp)) {
        BasicText(
            text = title,
            style = ZappTheme.typography.sectionTitle.copy(color = c.text),
        )
        Spacer(Modifier.height(6.dp))
        BasicText(
            text = body,
            style = ZappTheme.typography.body.copy(color = c.textMuted),
        )
    }
}

@PreviewScreens
@Composable
private fun BackgroundDeliverySettingsPreview() =
    ZcashTheme {
        BackgroundDeliverySettingsView(
            state =
                BackgroundDeliverySettingsState(
                    isEnabled = true,
                    onSaveClick = {},
                    onBack = {},
                ),
        )
    }
