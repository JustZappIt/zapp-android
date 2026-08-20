// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.profile

import co.electriccoin.zcash.ui.common.security.PinVerifyState
import co.electriccoin.zcash.ui.design.util.StringResource

data class ChatProfileState(
    val title: StringResource,
    val displayName: String?,
    val publicKey: String?,
    val isKeyCopied: Boolean,
    val onEditDisplayNameClick: () -> Unit,
    val onCopyPublicKeyClick: () -> Unit,
    val onWalletAddressClick: () -> Unit,
    val onSeedPhraseClick: () -> Unit,
    val onP2pKeyClick: () -> Unit,
    val onDeleteClick: () -> Unit,
    val onBack: () -> Unit,
    val editNameDialog: ChatProfileEditNameDialogState?,
    val deleteDialog: ChatProfileDeleteDialogState?,
    val seedPhraseDialog: ChatProfileSeedPhraseDialogState?,
    val pinVerify: PinVerifyState?,
)

data class ChatProfileEditNameDialogState(
    val value: String,
    val canSave: Boolean,
    val isSaving: Boolean,
    val error: StringResource?,
    val onValueChange: (String) -> Unit,
    val onSave: () -> Unit,
    val onDismiss: () -> Unit,
)

data class ChatProfileDeleteDialogState(
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
)

data class ChatProfileSeedPhraseDialogState(
    val words: List<String>,
    val onDismiss: () -> Unit,
)
