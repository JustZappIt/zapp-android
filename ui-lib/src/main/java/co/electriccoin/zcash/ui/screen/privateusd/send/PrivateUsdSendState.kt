// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.send

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdInfo

internal enum class PrivateUsdSendPhase { FORM, REVIEW, SENDING, DONE }

internal data class PrivateUsdSendState(
    val phase: PrivateUsdSendPhase,
    val isWithdrawal: Boolean,
    val onModeSelect: (Int) -> Unit,
    val assets: List<String>,
    val selectedAsset: Int,
    val onAssetSelect: (Int) -> Unit,
    /** In dollars. */
    val amount: NumberTextFieldState,
    /** Under the amount: what's wrong with it, or what it's worth in the user's currency. */
    val amountNote: StringResource?,
    val isAmountInvalid: Boolean,
    val available: StringResource?,
    val onMax: () -> Unit,
    val recipient: String,
    val onRecipientChange: (String) -> Unit,
    val recipientError: StringResource?,
    val review: PrivateUsdSendReviewState?,
    /** From 0 to 1 while the proof is being built. */
    val proofProgress: Float?,
    val done: PrivateUsdSendDoneState?,
    val error: StringResource?,
    val info: PrivateUsdInfo,
    val primaryButton: ButtonState,
    val onBack: () -> Unit,
)

internal data class PrivateUsdSendReviewState(
    val amount: StringResource,
    val railgunFee: StringResource?,
    val networkFee: StringResource,
    val receives: StringResource,
    val to: String,
    val paidByTestAccount: Boolean,
)

internal data class PrivateUsdSendDoneState(
    val body: StringResource,
    val explorerUrl: String?,
)
