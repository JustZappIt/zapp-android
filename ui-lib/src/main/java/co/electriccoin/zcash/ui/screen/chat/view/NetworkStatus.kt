// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ZashiModalBottomSheet
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.screen.chat.list.ChatListConnectionStatus
import co.electriccoin.zcash.ui.screen.chat.list.ChatListDhtHealth
import co.electriccoin.zcash.ui.screen.chat.model.ConnectionDetailsUi

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NetworkDetailsSheet(
    connectionStatus: ChatListConnectionStatus,
    peerCount: Int,
    dhtHealth: ChatListDhtHealth,
    connectionDetails: ConnectionDetailsUi?,
    onDismiss: () -> Unit
) {
    val errorColor = ZappTheme.colors.danger
    val okColor = ZappTheme.colors.success
    val warnColor = ZappTheme.colors.accent
    val mutedColor = ZappTheme.colors.textMuted

    ZashiModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ZappTheme.colors.bg,
        scrimColor = ZappTheme.colors.overlay,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.chat_settings_section_network),
                style = ZappTheme.typography.sectionTitle,
                color = ZappTheme.colors.text,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            NetworkSectionHeader(stringResource(R.string.chat_network_section_connection))

            NetworkDetailRow(
                icon = Icons.Default.Wifi,
                label = stringResource(R.string.chat_network_label_status),
                value = connectionStatusText(connectionStatus),
                valueColor =
                    when (connectionStatus) {
                        ChatListConnectionStatus.CONNECTED -> okColor
                        ChatListConnectionStatus.CONNECTING -> warnColor
                        else -> errorColor
                    }
            )

            connectionDetails?.let { details ->
                NetworkDetailRow(
                    icon = Icons.Default.Router,
                    label = stringResource(R.string.chat_network_label_reachability),
                    value = reachabilityText(details),
                    valueColor =
                        when {
                            details.dhtFirewalled == false -> okColor
                            details.dhtFirewalled == true || details.dhtRandomized == true -> warnColor
                            else -> mutedColor
                        }
                )

                NetworkDetailRow(
                    icon = Icons.Default.Backup,
                    label = stringResource(R.string.chat_network_label_backup),
                    value =
                        when {
                            details.relaysConnected > 0 -> stringResource(R.string.chat_network_value_backup_connected)
                            details.relaysTotal > 0 -> stringResource(R.string.chat_network_value_backup_unavailable)
                            else -> stringResource(R.string.chat_network_value_backup_off)
                        },
                    valueColor =
                        when {
                            details.relaysConnected > 0 -> okColor
                            details.relaysTotal > 0 -> warnColor
                            else -> mutedColor
                        }
                )
            }

            HorizontalDivider(color = ZappTheme.colors.border.copy(alpha = 0.3f))

            NetworkSectionHeader(stringResource(R.string.chat_network_section_peers))

            NetworkDetailRow(
                icon = Icons.Default.People,
                label = stringResource(R.string.chat_settings_label_peers),
                value = peerCount.toString(),
                valueColor = if (peerCount > 0) okColor else mutedColor
            )

            connectionDetails?.let { details ->
                NetworkDetailRow(
                    icon = Icons.Default.Cable,
                    label = stringResource(R.string.chat_network_label_tcp_connections),
                    value = details.globalConnections.toString()
                )
            }

            NetworkDetailRow(
                icon = Icons.Default.Hub,
                label = stringResource(R.string.chat_room_chip_dht),
                value =
                    when (dhtHealth) {
                        ChatListDhtHealth.HEALTHY -> stringResource(R.string.chat_settings_dht_healthy)
                        ChatListDhtHealth.DEGRADED -> stringResource(R.string.chat_settings_dht_degraded)
                        ChatListDhtHealth.CRITICAL -> stringResource(R.string.chat_settings_dht_critical)
                    },
                valueColor =
                    when (dhtHealth) {
                        ChatListDhtHealth.HEALTHY -> okColor
                        ChatListDhtHealth.DEGRADED -> warnColor
                        ChatListDhtHealth.CRITICAL -> errorColor
                    }
            )

            connectionDetails?.let { details ->
                NetworkDetailRow(
                    icon = Icons.Default.DeviceHub,
                    label = stringResource(R.string.chat_network_label_dht_nodes),
                    value = details.rtNodes.toString(),
                    valueColor = if (details.rtNodes > 0) okColor else mutedColor
                )

                HorizontalDivider(color = ZappTheme.colors.border.copy(alpha = 0.3f))

                NetworkSectionHeader(stringResource(R.string.chat_network_section_messages))

                NetworkDetailRow(
                    icon = Icons.Default.Schedule,
                    label = stringResource(R.string.chat_network_label_pending),
                    value =
                        if (details.pendingMessageCount > 0) {
                            stringResource(
                                R.string.chat_network_value_pending_fmt,
                                details.pendingMessageCount,
                                details.pendingQueues
                            )
                        } else {
                            stringResource(R.string.chat_network_value_pending_none)
                        },
                    valueColor = if (details.pendingMessageCount > 0) warnColor else okColor
                )

                NetworkDetailRow(
                    icon = Icons.AutoMirrored.Filled.Chat,
                    label = stringResource(R.string.chat_network_label_conversations),
                    value =
                        stringResource(
                            R.string.chat_network_value_conversations_fmt,
                            details.directConversations,
                            details.groupConversations
                        )
                )

                NetworkDetailRow(
                    icon = Icons.Default.PersonAdd,
                    label = stringResource(R.string.chat_network_label_invites),
                    value = details.pendingInvites.toString(),
                    valueColor = if (details.pendingInvites > 0) warnColor else mutedColor
                )
            }
        }
    }
}

@Composable
private fun connectionStatusText(status: ChatListConnectionStatus): String =
    when (status) {
        ChatListConnectionStatus.CONNECTED -> stringResource(R.string.chat_settings_connection_connected)
        ChatListConnectionStatus.CONNECTING -> stringResource(R.string.chat_settings_connection_connecting)
        ChatListConnectionStatus.DISCONNECTED -> stringResource(R.string.chat_settings_connection_disconnected)
        ChatListConnectionStatus.ERROR -> stringResource(R.string.chat_settings_connection_error)
    }

@Composable
private fun reachabilityText(details: ConnectionDetailsUi): String =
    when {
        details.dhtFirewalled == false -> stringResource(R.string.chat_network_value_reachability_direct)
        details.dhtRandomized == true -> stringResource(R.string.chat_network_value_reachability_strict)
        details.dhtFirewalled == true -> stringResource(R.string.chat_network_value_reachability_nat)
        else -> stringResource(R.string.chat_network_value_reachability_unknown)
    }

@Composable
private fun NetworkSectionHeader(text: String) {
    Text(
        text = text,
        style = ZappTheme.typography.groupLabel,
        color = ZappTheme.colors.textMuted
    )
}

@Composable
private fun NetworkDetailRow(
    icon: ImageVector,
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified
) {
    val effectiveValueColor =
        if (valueColor == Color.Unspecified) {
            ZappTheme.colors.text
        } else {
            valueColor
        }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = ZappTheme.colors.textMuted
            )
            Text(
                text = label,
                style = ZappTheme.typography.body,
                color = ZappTheme.colors.textMuted
            )
        }
        Text(
            text = value,
            style = ZappTheme.typography.body,
            color = effectiveValueColor
        )
    }
}
