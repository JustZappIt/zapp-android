// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.viewingkeyexport

import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.ui.common.security.PinVerifyState
import co.electriccoin.zcash.ui.common.usecase.ViewingKeyExportAccount
import co.electriccoin.zcash.ui.common.usecase.ViewingKeyExportData
import co.electriccoin.zcash.ui.common.usecase.ViewingKeyType

internal data class ViewingKeyExportState(
    val accounts: List<ViewingKeyExportAccount>,
    val selectedAccountId: AccountUuid?,
    val selectedKeyType: ViewingKeyType,
    val isAcknowledged: Boolean,
    val isLoading: Boolean,
    val isAuthenticating: Boolean,
    val isCopied: Boolean,
    val revealedKey: ViewingKeyExportData?,
    val error: ViewingKeyExportError?,
    val pinVerify: PinVerifyState?,
    val onAccountSelected: (AccountUuid) -> Unit,
    val onKeyTypeSelected: (ViewingKeyType) -> Unit,
    val onAcknowledgementChanged: (Boolean) -> Unit,
    val onReveal: () -> Unit,
    val onCopy: () -> Unit,
    val onShare: (String) -> Unit,
    val onHide: () -> Unit,
    val onBack: () -> Unit,
) {
    val selectedAccount: ViewingKeyExportAccount?
        get() = accounts.firstOrNull { it.accountId == selectedAccountId }

    val isSelectedKeyAvailable: Boolean
        get() = selectedKeyType in selectedAccount?.availableKeyTypes.orEmpty()

    val canReveal: Boolean
        get() = isAcknowledged && isSelectedKeyAvailable && !isLoading && !isAuthenticating
}

internal enum class ViewingKeyExportError {
    LOAD_FAILED,
    AUTHENTICATION_FAILED,
    KEY_UNAVAILABLE,
    SHARE_FAILED,
}
