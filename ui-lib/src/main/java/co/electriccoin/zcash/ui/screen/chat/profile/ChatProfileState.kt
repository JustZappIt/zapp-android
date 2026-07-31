// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.profile

import co.electriccoin.zcash.ui.design.util.StringResource

data class ChatProfileState(
    val title: StringResource,
    val activeTab: ChatProfileTab,
    val walletSubTab: ChatProfileWalletSubTab,
    val displayName: String?,
    val publicKey: String?,
    val shieldedAddress: String?,
    val transparentAddress: String?,
    val baseAddress: String?,
    val isKeyCopied: Boolean,
    val isAddressCopied: Boolean,
    val isBaseAddressCopied: Boolean,
    val onMainTabSelected: (ChatProfileTab) -> Unit,
    val onWalletSubTabSelected: (ChatProfileWalletSubTab) -> Unit,
    val onEditDisplayNameClick: () -> Unit,
    val onCopyPublicKeyClick: () -> Unit,
    val onCopyAddressClick: () -> Unit,
    val onCopyBaseAddressClick: () -> Unit,
    val onSeedPhraseClick: () -> Unit,
    val onP2pKeyClick: () -> Unit,
    val onDeleteClick: () -> Unit,
    val onBack: () -> Unit,
    val editNameDialog: ChatProfileEditNameDialogState?,
    val deleteDialog: ChatProfileDeleteDialogState?,
    val seedPhraseDialog: ChatProfileSeedPhraseDialogState?,
    val p2pKeyDialog: ChatProfileP2pKeyDialogState?,
    val pinVerify: ChatProfilePinVerifyState?,
)

enum class ChatProfileTab { MESSAGING_ID, WALLET_ADDRESS }

enum class ChatProfileWalletSubTab { SHIELDED, TRANSPARENT }

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

data class ChatProfileP2pKeyDialogState(
    val address: String,
    val privateKeyHex: String,
    val onCopyAddress: () -> Unit,
    val onCopyPrivateKey: () -> Unit,
    val onDismiss: () -> Unit,
)

data class ChatProfilePinVerifyState(
    val hasError: Boolean,
    val lockoutSecondsRemaining: Int,
    val onPinSubmit: (String) -> Unit,
    val onCancel: () -> Unit,
)
