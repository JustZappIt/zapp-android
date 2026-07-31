// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.contacts

import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.chat.model.ChatContact
import co.electriccoin.zcash.ui.screen.chat.view.BlockUserDialogState

data class ChatContactsState(
    val title: StringResource,
    val contacts: List<ChatContact>,
    val onStartChat: (publicKey: String) -> Unit,
    val onAddSheetOpen: () -> Unit,
    val onEditSheetOpen: (ChatContact) -> Unit,
    val onBack: () -> Unit,
    val addSheet: AddChatContactState?,
    val editSheet: EditChatContactState?,
    val blockDialog: BlockUserDialogState?,
)
