// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

data class BlockUserDialogState(
    val displayName: String,
    val isUnblock: Boolean,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
)
