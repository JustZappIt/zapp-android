// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.convert

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdInfo

internal enum class PrivateUsdConvertPhase { AMOUNT, REVIEW }

internal data class PrivateUsdConvertState(
    val phase: PrivateUsdConvertPhase,
    /** Total ZEC spend, including its network fee. */
    val amount: NumberTextFieldState,
    /** Under the amount: what it's worth in the user's currency, or the limits. */
    val amountNote: StringResource,
    val isAmountInvalid: Boolean,
    val zecAvailable: StringResource?,
    val quote: PrivateUsdQuoteState?,
    val isQuoting: Boolean,
    /** Why it can't go on. */
    val message: StringResource?,
    val info: PrivateUsdInfo,
    val primaryButton: ButtonState,
    val onBack: () -> Unit,
    val currencySymbol: String = "",
    val usdAvailable: StringResource? = null,
    val usdEstimate: java.math.BigDecimal? = null,
    val onMax: (() -> Unit)? = null,
)

internal data class PrivateUsdQuoteState(
    val pay: StringResource,
    val networkFee: StringResource?,
    val receive: StringResource,
    val fees: StringResource,
    val refreshesIn: StringResource,
)
