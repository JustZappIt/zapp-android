// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.walletaddress

import co.electriccoin.zcash.ui.design.util.StringResource

data class ChatWalletAddressState(
    val addresses: List<ChatWalletAddressItem>,
    val onBack: () -> Unit,
)

data class ChatWalletAddressItem(
    val label: StringResource,
    val caption: StringResource,
    val address: String,
    val hasQrCode: Boolean,
    val isCopied: Boolean,
    val onCopyClick: () -> Unit,
)
