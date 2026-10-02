// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.reverse

import co.electriccoin.zcash.ui.common.security.PinVerifyState
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdInfo
import co.electriccoin.zcash.ui.screen.privateusd.progress.PrivateUsdProgressState

internal data class PrivateUsdReverseState(
    /** The private dollars to convert, in [currencySymbol]'s currency. */
    val amount: NumberTextFieldState,
    val isAmountInvalid: Boolean,
    /** The least and the most one conversion takes. */
    val amountNote: StringResource,
    val currencySymbol: String,
    val usdAvailable: StringResource,
    val zecAvailable: StringResource?,
    /** The ZEC a quote brings home. */
    val receiveEstimate: NumberTextFieldInnerState,
    val isQuoting: Boolean,
    val onMax: (() -> Unit)?,
    val canSwitchDirection: Boolean,
    /** Set while the user looks over a quote before converting. */
    val review: PrivateUsdReverseReviewState?,
    /** Set once it's going ahead, under way or over. */
    val progress: PrivateUsdProgressState?,
    /** Opens every refunded conversion, including earlier ones. */
    val refunds: ButtonState?,
    val error: StringResource?,
    val info: PrivateUsdInfo,
    /** Null while the conversion goes on by itself. */
    val primary: ButtonState?,
    val isBackEnabled: Boolean,
    val pinVerify: PinVerifyState?,
    val onBack: () -> Unit,
    val isZecBalanceLoading: Boolean = false,
    val isUsdBalanceLoading: Boolean = false,
    val usdBalanceError: StringResource? = null,
    val onRefreshBalance: () -> Unit = {},
)

internal data class PrivateUsdReverseReviewState(
    val debit: StringResource,
    val escrow: StringResource,
    val railgunFee: StringResource,
    /** Null while a test account pays the broadcast. */
    val broadcasterFee: StringResource?,
    val receive: StringResource,
)
