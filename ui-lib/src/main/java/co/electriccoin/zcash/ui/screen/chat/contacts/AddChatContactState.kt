// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.contacts

import androidx.compose.ui.text.input.TextFieldValue
import co.electriccoin.zcash.ui.design.util.StringResource

data class AddChatContactState(
    val name: TextFieldValue,
    val publicKey: TextFieldValue,
    val walletAddress: TextFieldValue,
    val transparentAddr: TextFieldValue,
    val evmAddr: TextFieldValue,
    val solanaAddr: TextFieldValue,
    val showAdditionalAddresses: Boolean,
    val error: StringResource?,
    val isValidKey: Boolean,
    val cleanedKey: String,
    val onNameChange: (TextFieldValue) -> Unit,
    val onPublicKeyChange: (TextFieldValue) -> Unit,
    val onWalletAddressChange: (TextFieldValue) -> Unit,
    val onTransparentAddrChange: (TextFieldValue) -> Unit,
    val onEvmAddrChange: (TextFieldValue) -> Unit,
    val onSolanaAddrChange: (TextFieldValue) -> Unit,
    val onToggleAdditionalAddresses: () -> Unit,
    val onScanPublicKey: () -> Unit,
    val onScanWalletAddress: () -> Unit,
    val onScanAddressField: (addrType: String) -> Unit,
    val onSave: () -> Unit,
    val onDismiss: () -> Unit,
)
