// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.support

import android.net.Uri

/**
 * Direction of a message in the support chat. Decouples bubble alignment from `isFromMe`
 * so that bot-prefixed messages the user's device sent appear on the agent side without
 * lying about ownership in the underlying [ChatMessage].
 */
enum class SupportMessageOrigin {
    /** Typed by the local user. Renders right-aligned. */
    USER,

    /** Sent by the remote support agent. Renders left-aligned. */
    AGENT,

    /** Automated greeting/notice with the `[Zapp]:` prefix; renders left-aligned. */
    BOT,
}

data class SupportUiMessage(
    val id: String,
    val content: String,
    val origin: SupportMessageOrigin,
    val timestamp: Long,
) {
    val isFromLocalUser: Boolean get() = origin == SupportMessageOrigin.USER
}

sealed interface SupportChatUiState {
    data object Loading : SupportChatUiState

    /**
     * New ticket — user has not yet selected a category. [isSubmitting] is true while the
     * createConversation + sendMessage round-trip is in flight; the picker should disable
     * tapping and show a progress indicator so a slow network doesn't look like a freeze.
     */
    data class SelectCategory(
        val isSubmitting: Boolean = false
    ) : SupportChatUiState

    /** Category has been selected; free-form chat is active. */
    data class Chat(
        val messages: List<SupportUiMessage>,
        val input: String,
    ) : SupportChatUiState
}

data class SupportChatScreenState(
    val uiState: SupportChatUiState,
    val onCategorySelected: (SupportCategory) -> Unit,
    val onInputChange: (String) -> Unit,
    val onSend: () -> Unit,
    val onAttach: () -> Unit,
    val onMediaCommitted: (Uri) -> Unit,
    val onLeave: () -> Unit,
    val onBack: () -> Unit,
    val leaveDialog: SupportLeaveDialogState?,
    val mediaSheet: SupportMediaSheetState?,
)

data class SupportMediaSheetState(
    val onChooseMedia: () -> Unit,
    val onAttachFile: () -> Unit,
    val onTakePhoto: () -> Unit,
    val onDismiss: () -> Unit,
)

data class SupportLeaveDialogState(
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
)
