// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

data class BlockUserDialogState(
    val displayName: String,
    val isUnblock: Boolean,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
)
