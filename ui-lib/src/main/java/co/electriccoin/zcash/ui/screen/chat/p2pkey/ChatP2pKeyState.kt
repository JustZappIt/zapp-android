// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.p2pkey

import co.electriccoin.zcash.ui.screen.chat.common.ChatPinVerifyState

data class ChatP2pKeyState(
    val smartAccountAddress: String?,
    val ownerKey: ChatP2pOwnerKey?,
    val copiedValue: String?,
    val onCopyClick: (String) -> Unit,
    val onRevealClick: () -> Unit,
    val onBack: () -> Unit,
    val pinVerify: ChatPinVerifyState?,
)

data class ChatP2pOwnerKey(
    val address: String,
    val privateKeyHex: String,
)
