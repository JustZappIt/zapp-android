// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.contacts

import androidx.compose.ui.text.input.TextFieldValue
import co.electriccoin.zcash.ui.design.util.StringResource

data class EditChatContactState(
    val publicKey: String,
    val originalName: String,
    val originalWalletAddress: String,
    val name: TextFieldValue,
    val walletAddress: TextFieldValue,
    val transparentAddr: TextFieldValue,
    val evmAddr: TextFieldValue,
    val solanaAddr: TextFieldValue,
    val showAdditionalAddresses: Boolean,
    val showDeleteConfirm: Boolean,
    val error: StringResource?,
    val isSaveEnabled: Boolean,
    val onNameChange: (TextFieldValue) -> Unit,
    val onWalletAddressChange: (TextFieldValue) -> Unit,
    val onTransparentAddrChange: (TextFieldValue) -> Unit,
    val onEvmAddrChange: (TextFieldValue) -> Unit,
    val onSolanaAddrChange: (TextFieldValue) -> Unit,
    val onToggleAdditionalAddresses: () -> Unit,
    val onScanWalletAddress: () -> Unit,
    val onScanAddressField: (addrType: String) -> Unit,
    val onSave: () -> Unit,
    val onRequestDelete: () -> Unit,
    val onCancelDelete: () -> Unit,
    val onConfirmDelete: () -> Unit,
    val onDismiss: () -> Unit,
    val onBlock: (() -> Unit)? = null,
    val isBlocked: Boolean = false,
    val canDelete: Boolean = true,
)
