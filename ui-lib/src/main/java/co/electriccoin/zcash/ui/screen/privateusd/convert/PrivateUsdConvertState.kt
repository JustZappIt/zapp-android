// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.convert

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.util.StringResource

internal enum class PrivateUsdConvertPhase { AMOUNT, REVIEW }

internal data class PrivateUsdConvertState(
    val phase: PrivateUsdConvertPhase,
    /** The presets, then "Other". */
    val amounts: List<StringResource>,
    val selectedAmount: Int?,
    val onAmountSelect: (Int) -> Unit,
    /** Shown while "Other" is picked. */
    val customAmount: NumberTextFieldState?,
    val limits: StringResource,
    val zecAvailable: StringResource?,
    val quote: PrivateUsdQuoteState?,
    val isQuoting: Boolean,
    val message: StringResource?,
    val isMessageDanger: Boolean,
    val duration: StringResource,
    val primaryButton: ButtonState,
    val onBack: () -> Unit,
)

internal data class PrivateUsdQuoteState(
    val pay: StringResource,
    val networkFee: StringResource?,
    val receive: StringResource,
    val fees: StringResource,
    val refreshesIn: StringResource,
)
