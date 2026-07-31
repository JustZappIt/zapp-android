// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.runtime.Composable
import co.electriccoin.zcash.ui.design.component.zapp.ZappChipVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappStatusChip
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.screen.chat.list.ChatListChipVariant
import co.electriccoin.zcash.ui.screen.chat.list.ChatListNetworkChipState

@Composable
internal fun NetworkChip(state: ChatListNetworkChipState) {
    val c = ZappTheme.colors
    val (variant, dotColor) =
        when (state.variant) {
            ChatListChipVariant.Success -> ZappChipVariant.Success to c.success
            ChatListChipVariant.Accent -> ZappChipVariant.Accent to c.accent
            ChatListChipVariant.Danger -> ZappChipVariant.Danger to c.danger
        }
    ZappStatusChip(
        text = state.text.getValue(),
        variant = variant,
        dotColor = dotColor,
        onClick = state.onClick,
    )
}
