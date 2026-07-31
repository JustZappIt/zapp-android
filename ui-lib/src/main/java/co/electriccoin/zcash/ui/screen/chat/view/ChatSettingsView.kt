// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappGroupHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappRow
import co.electriccoin.zcash.ui.design.component.zapp.ZappRowDivider
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappToggle
import co.electriccoin.zcash.ui.design.component.zapp.initialsOf
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.screen.chat.common.rememberNotificationPermissionRequester
import co.electriccoin.zcash.ui.screen.chat.list.ChatListConnectionStatus
import co.electriccoin.zcash.ui.screen.chat.list.ChatListDhtHealth
import co.electriccoin.zcash.ui.screen.chat.settings.ChatSettingsDeleteDialogState
import co.electriccoin.zcash.ui.screen.chat.settings.ChatSettingsEditNameDialogState
import co.electriccoin.zcash.ui.screen.chat.settings.ChatSettingsState

@Composable
internal fun ChatSettingsView(state: ChatSettingsState, modifier: Modifier = Modifier) {
    val c = ZappTheme.colors
    val requestNotificationsPermission =
        rememberNotificationPermissionRequester { granted ->
            if (granted) state.onNotificationsToggle(true)
        }
    val requestBackgroundDeliveryPermission =
        rememberNotificationPermissionRequester { granted ->
            if (granted) state.onBackgroundPushToggle(true)
        }
    val onNotificationsClick = {
        if (state.notificationsEnabled) {
            state.onNotificationsToggle(false)
        } else {
            requestNotificationsPermission()
        }
    }
    val onBackgroundDeliveryClick = {
        if (state.backgroundPushEnabled) {
            state.onBackgroundPushToggle(false)
        } else {
            requestBackgroundDeliveryPermission()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { ZappScreenHeader(title = state.title.getValue()) },
        bottomBar = {
            ZappBottomActionBar(
                onBack = state.onBack,
                primaryAction = {
                    ZappButton(
                        text = state.deleteLabel.getValue(),
                        variant = ZappButtonVariant.Danger,
                        onClick = state.onDeleteClick,
                    )
                },
            )
        },
        containerColor = c.bg,
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            state.displayName?.let { name ->
                val pk = state.publicKey.orEmpty()
                IdentitySection(
                    displayName = name,
                    publicKey = pk,
                    isPublicKeyCopied = state.isPublicKeyCopied,
                    onEditClick = state.onEditDisplayNameClick,
                    onCopyClick = state.onCopyPublicKeyClick,
                )
                ZappRowDivider()
            }

            ZappGroupHeader(text = stringResource(R.string.chat_settings_section_account))
            ZappRow(
                title = stringResource(R.string.chat_settings_row_profile),
                subtitle = stringResource(R.string.chat_settings_row_profile_subtitle),
                onClick = state.onProfileClick,
            )
            ZappRowDivider(inset = true)
            ZappRow(
                title = stringResource(R.string.chat_settings_row_contacts),
                subtitle = stringResource(R.string.chat_settings_row_contacts_subtitle),
                onClick = state.onContactsClick,
            )
            ZappRowDivider()

            ZappGroupHeader(text = stringResource(R.string.chat_settings_section_network))
            NetworkSection(
                connectionStatus = state.connectionStatus,
                dhtHealth = state.dhtHealth,
                peerCount = state.peerCount,
            )
            ZappRowDivider()

            ZappGroupHeader(text = stringResource(R.string.chat_settings_section_notifications))
            ZappRow(
                title = stringResource(R.string.chat_settings_notifications_toggle_title),
                subtitle = stringResource(R.string.chat_settings_notifications_toggle_subtitle),
                trailing = {
                    ZappToggle(
                        checked = state.notificationsEnabled,
                        onClick = onNotificationsClick,
                    )
                },
                onClick = onNotificationsClick,
            )
            ZappRowDivider(inset = true)
            ZappRow(
                title = stringResource(R.string.chat_settings_background_push_toggle_title),
                subtitle = stringResource(R.string.chat_settings_background_push_toggle_subtitle),
                trailing = {
                    ZappToggle(
                        checked = state.backgroundPushEnabled,
                        onClick = onBackgroundDeliveryClick,
                    )
                },
                onClick = onBackgroundDeliveryClick,
            )
            ZappRowDivider()

            ZappGroupHeader(text = stringResource(R.string.chat_settings_section_privacy))
            ZappRow(
                title = stringResource(R.string.chat_settings_read_receipts_toggle_title),
                subtitle = stringResource(R.string.read_receipts_settings_row_subtitle),
                onClick = state.onReadReceiptsClick,
            )
            ZappRowDivider(inset = true)
            ZappRow(
                title = stringResource(R.string.chat_settings_online_status_toggle_title),
                subtitle = stringResource(R.string.online_status_settings_row_subtitle),
                onClick = state.onOnlineStatusClick,
            )
            ZappRowDivider()

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    state.editNameDialog?.let { EditDisplayNameDialog(state = it) }
    state.deleteDialog?.let { DeleteIdentityDialog(state = it) }
}

@Composable
private fun IdentitySection(
    displayName: String,
    publicKey: String,
    isPublicKeyCopied: Boolean,
    onEditClick: () -> Unit,
    onCopyClick: () -> Unit,
) {
    val c = ZappTheme.colors
    val initials = remember(displayName) { initialsOf(displayName) }
    val editLabel = stringResource(R.string.chat_settings_edit_name_content_description)
    val copyLabel = stringResource(R.string.chat_settings_copy_content_description)

    ZappGroupHeader(text = stringResource(R.string.chat_settings_section_identity))

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(c.surface),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(72.dp)
                        .background(c.accent, RectangleShape),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = initials,
                    style = ZappTheme.typography.sectionTitle.copy(color = c.onAccent),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                BasicText(
                    text = displayName,
                    style = ZappTheme.typography.sectionTitle.copy(color = c.text),
                )
                IconButton(onClick = onEditClick, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = editLabel,
                        tint = c.textMuted,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (publicKey.isNotEmpty()) {
                Row(
                    modifier =
                        Modifier
                            .clickable(onClick = onCopyClick)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        text =
                            "${publicKey.take(PUBLIC_KEY_HEAD)}...${
                                publicKey.takeLast(PUBLIC_KEY_TAIL)
                            }",
                        style = ZappTheme.typography.mono.copy(color = c.textMuted),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector =
                            if (isPublicKeyCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = copyLabel,
                        tint = if (isPublicKeyCopied) c.success else c.textMuted,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NetworkSection(
    connectionStatus: ChatListConnectionStatus,
    dhtHealth: ChatListDhtHealth,
    peerCount: Int,
) {
    val c = ZappTheme.colors
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(c.surface)
                .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NetworkInfoRow(
                label = stringResource(R.string.chat_settings_label_connection),
                value = connectionStatusLabel(connectionStatus),
                valueColor = connectionStatusColor(connectionStatus),
            )
            NetworkInfoRow(
                label = stringResource(R.string.chat_settings_label_dht_health),
                value = dhtHealthLabel(dhtHealth),
                valueColor = dhtHealthColor(dhtHealth),
            )
            NetworkInfoRow(
                label = stringResource(R.string.chat_settings_label_peers),
                value = peerCount.toString(),
                valueColor = if (peerCount > 0) c.success else c.textMuted,
            )
            NetworkInfoRow(
                label = stringResource(R.string.chat_settings_label_protocol),
                value = stringResource(R.string.chat_settings_protocol_value),
                valueColor = c.textMuted,
            )
            NetworkInfoRow(
                label = stringResource(R.string.chat_settings_label_encryption),
                value = stringResource(R.string.chat_settings_encryption_value),
                valueColor = c.textMuted,
            )
        }
    }
}

@Composable
private fun connectionStatusLabel(status: ChatListConnectionStatus): String =
    when (status) {
        ChatListConnectionStatus.CONNECTED -> stringResource(R.string.chat_settings_connection_connected)
        ChatListConnectionStatus.CONNECTING -> stringResource(R.string.chat_settings_connection_connecting)
        ChatListConnectionStatus.DISCONNECTED -> stringResource(R.string.chat_settings_connection_disconnected)
        ChatListConnectionStatus.ERROR -> stringResource(R.string.chat_settings_connection_error)
    }

@Composable
private fun connectionStatusColor(status: ChatListConnectionStatus): Color {
    val c = ZappTheme.colors
    return when (status) {
        ChatListConnectionStatus.CONNECTED -> c.success
        ChatListConnectionStatus.CONNECTING -> c.accent
        else -> c.danger
    }
}

@Composable
private fun dhtHealthLabel(health: ChatListDhtHealth): String =
    when (health) {
        ChatListDhtHealth.HEALTHY -> stringResource(R.string.chat_settings_dht_healthy)
        ChatListDhtHealth.DEGRADED -> stringResource(R.string.chat_settings_dht_degraded)
        ChatListDhtHealth.CRITICAL -> stringResource(R.string.chat_settings_dht_critical)
    }

@Composable
private fun dhtHealthColor(health: ChatListDhtHealth): Color {
    val c = ZappTheme.colors
    return when (health) {
        ChatListDhtHealth.HEALTHY -> c.success
        ChatListDhtHealth.DEGRADED -> c.accent
        ChatListDhtHealth.CRITICAL -> c.danger
    }
}

@Composable
private fun NetworkInfoRow(label: String, value: String, valueColor: Color) {
    val c = ZappTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, style = ZappTheme.typography.body.copy(color = c.textMuted))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(modifier = Modifier.size(6.dp).background(valueColor, RectangleShape))
            BasicText(value, style = ZappTheme.typography.body.copy(color = valueColor))
        }
    }
}

@Composable
private fun EditDisplayNameDialog(state: ChatSettingsEditNameDialogState) {
    AlertDialog(
        onDismissRequest = state.onDismiss,
        title = { Text(stringResource(R.string.chat_settings_edit_name_dialog_title)) },
        text = {
            OutlinedTextField(
                value = state.value,
                onValueChange = state.onValueChange,
                label = { Text(stringResource(R.string.chat_settings_edit_name_dialog_placeholder)) },
                singleLine = true,
                isError = state.error != null,
                supportingText = state.error?.let { error -> { Text(error.getValue()) } },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = state.onSave, enabled = state.canSave) {
                Text(
                    stringResource(
                        if (state.isSaving) {
                            R.string.chat_display_name_updating
                        } else {
                            R.string.chat_settings_edit_name_dialog_save
                        }
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = state.onDismiss) {
                Text(stringResource(R.string.chat_settings_edit_name_dialog_cancel))
            }
        },
    )
}

@Composable
private fun DeleteIdentityDialog(state: ChatSettingsDeleteDialogState) {
    AlertDialog(
        onDismissRequest = state.onDismiss,
        title = { Text(stringResource(R.string.chat_settings_delete_dialog_title)) },
        text = { Text(stringResource(R.string.chat_settings_delete_dialog_message)) },
        confirmButton = {
            TextButton(onClick = state.onConfirm) {
                Text(stringResource(R.string.chat_settings_delete_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = state.onDismiss) {
                Text(stringResource(R.string.chat_settings_delete_dialog_cancel))
            }
        },
    )
}

private const val PUBLIC_KEY_HEAD = 10
private const val PUBLIC_KEY_TAIL = 6
