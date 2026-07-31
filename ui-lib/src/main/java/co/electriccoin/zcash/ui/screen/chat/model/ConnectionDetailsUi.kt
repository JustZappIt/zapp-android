// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.model

import xyz.justzappit.zappmessaging.ZappMessagingSDK

/**
 * UI-layer projection of [ZappMessagingSDK.ConnectionDetails] so that views and
 * Composables never need to import the SDK directly.
 */
data class ConnectionDetailsUi(
    val globalConnections: Int,
    val directConversations: Int,
    val groupConversations: Int,
    val pendingMessageCount: Int,
    val pendingQueues: Int,
    val pendingInvites: Int,
    val consecutiveFailures: Int,
    val dhtBootstrapped: Boolean,
    val dhtFirewalled: Boolean?,
    val dhtRandomized: Boolean?,
    val rtNodes: Int,
    val relayEnabled: Boolean,
    val relaysConnected: Int,
    val relaysTotal: Int,
) {
    companion object {
        fun from(details: ZappMessagingSDK.ConnectionDetails): ConnectionDetailsUi =
            ConnectionDetailsUi(
                globalConnections = details.globalConnections,
                directConversations = details.directConversations,
                groupConversations = details.groupConversations,
                pendingMessageCount = details.pendingMessageCount,
                pendingQueues = details.pendingQueues,
                pendingInvites = details.pendingInvites,
                consecutiveFailures = details.consecutiveFailures,
                dhtBootstrapped = details.dhtBootstrapped,
                dhtFirewalled = details.dhtFirewalled,
                dhtRandomized = details.dhtRandomized,
                rtNodes = details.rtNodes,
                relayEnabled = details.relayEnabled,
                relaysConnected = details.relaysConnected,
                relaysTotal = details.relaysTotal,
            )
    }
}
