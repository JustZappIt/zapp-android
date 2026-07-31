// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.identity

import co.electriccoin.zcash.ui.design.util.StringResource

data class ChatIdentitySetupState(
    val title: StringResource,
    val subtitle: StringResource,
    val displayName: String,
    val displayNamePlaceholder: StringResource,
    val submitLabel: StringResource,
    val isSubmitting: Boolean,
    val error: StringResource?,
    // Copy-pasteable failure details for support; null unless a derive failed.
    val diagnostic: String?,
    val onDisplayNameChange: (String) -> Unit,
    val onSubmit: () -> Unit,
)
