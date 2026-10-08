// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.send

import androidx.annotation.StringRes
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.security.PinVerifyState
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.TextFieldState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdInfo
import kotlinx.serialization.Serializable

internal enum class PrivateUsdSendPhase { FORM, REVIEW, SENDING, DONE }

/** Where the dollars go: to another Railgun address, privately, or out of Railgun to an Ethereum one. */
@Serializable
enum class PrivateUsdSendMode(
    @param:StringRes val title: Int,
    @param:StringRes val recipientLabel: Int,
    @param:StringRes val recipientHint: Int,
    @param:StringRes val reviewTitle: Int,
    @param:StringRes val confirm: Int,
    @param:StringRes val doneTitle: Int,
) {
    PRIVATE(
        title = R.string.private_usd_send_title,
        recipientLabel = R.string.private_usd_send_to_private,
        recipientHint = R.string.private_usd_send_hint_private,
        reviewTitle = R.string.private_usd_send_review_title_private,
        confirm = R.string.private_usd_action_send,
        doneTitle = R.string.private_usd_send_done_title_private,
    ),
    WITHDRAW(
        title = R.string.private_usd_withdraw_title,
        recipientLabel = R.string.private_usd_send_to_withdraw,
        recipientHint = R.string.private_usd_send_hint_withdraw,
        reviewTitle = R.string.private_usd_send_review_title_withdraw,
        confirm = R.string.private_usd_action_withdraw,
        doneTitle = R.string.private_usd_send_done_title_withdraw,
    ),
}

internal data class PrivateUsdSendState(
    val phase: PrivateUsdSendPhase,
    val mode: PrivateUsdSendMode,
    val onModeSelect: (PrivateUsdSendMode) -> Unit,
    /** The dollar tokens there are to send, shown when there's a choice. */
    val assets: List<PrivateUsdSendAssetState>,
    /** In dollars. */
    val amount: NumberTextFieldState,
    /** The dollar's symbol, before the amount. */
    val currencySymbol: String,
    /** Under the amount: what's wrong with it, or what it's worth in the user's currency. */
    val amountNote: StringResource?,
    val isAmountInvalid: Boolean,
    val available: StringResource?,
    val onMax: () -> Unit,
    val recipient: TextFieldState,
    val review: PrivateUsdSendReviewState?,
    /** From 0 to 1 while the proof is being built. */
    val proofProgress: Float?,
    val done: PrivateUsdSendDoneState?,
    val error: StringResource?,
    val info: PrivateUsdInfo,
    val primaryButton: ButtonState,
    val onBack: () -> Unit,
    val isBusy: Boolean = false,
    /** False while the send the user authorized runs. */
    val isBackEnabled: Boolean = true,
    val pinVerify: PinVerifyState? = null,
)

internal data class PrivateUsdSendAssetState(
    val symbol: String,
    val isSelected: Boolean,
    val onSelect: () -> Unit,
)

internal data class PrivateUsdSendReviewState(
    val token: String,
    val amount: StringResource,
    val railgunFee: PrivateUsdSendFee?,
    val networkFee: PrivateUsdSendFee?,
    val receives: StringResource,
    val to: String,
)

internal data class PrivateUsdSendFee(
    val label: StringResource,
    val amount: StringResource,
)

internal data class PrivateUsdSendDoneState(
    val body: StringResource,
    val onViewTransaction: (() -> Unit)?,
    /** Set while the send isn't in a block yet. */
    val note: StringResource?,
    val onViewOnRailscan: (() -> Unit)? = null,
)
