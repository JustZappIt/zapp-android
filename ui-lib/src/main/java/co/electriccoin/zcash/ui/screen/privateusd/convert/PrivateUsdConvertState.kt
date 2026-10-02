// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.convert

import co.electriccoin.zcash.ui.common.security.PinVerifyState
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdInfo

internal enum class PrivateUsdConvertPhase { AMOUNT, REVIEW }

internal data class PrivateUsdConvertState(
    val phase: PrivateUsdConvertPhase,
    /** The ZEC to spend in all, network fee included. */
    val amount: NumberTextFieldState,
    val isAmountInvalid: Boolean,
    val zecAvailable: StringResource?,
    val usdAvailable: StringResource,
    val currencySymbol: String,
    /** What the quote brings, in the user's currency. */
    val receiveEstimate: NumberTextFieldInnerState,
    val onMax: (() -> Unit)?,
    val quote: PrivateUsdQuoteState?,
    val isQuoting: Boolean,
    /** Why it can't go on. */
    val message: StringResource?,
    val canSwitchDirection: Boolean,
    val info: PrivateUsdInfo,
    val primaryButton: ButtonState,
    val isBackEnabled: Boolean,
    val pinVerify: PinVerifyState?,
    val onBack: () -> Unit,
    val isZecBalanceLoading: Boolean = false,
    val isUsdBalanceLoading: Boolean = false,
    val usdBalanceError: StringResource? = null,
    val onRefreshBalance: () -> Unit = {},
)

internal data class PrivateUsdQuoteState(
    val pay: StringResource,
    val networkFee: StringResource,
    val receive: StringResource,
    val fees: StringResource,
    /** How long the quote has left. */
    val expiry: StringResource,
)
