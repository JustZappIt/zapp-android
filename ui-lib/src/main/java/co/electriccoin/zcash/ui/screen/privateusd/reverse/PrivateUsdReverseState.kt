// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.reverse

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.privateusd.progress.PrivateUsdProgressState
import java.math.BigDecimal

internal data class PrivateUsdReverseState(
    val amount: NumberTextFieldState,
    val showAmount: Boolean,
    val status: StringResource,
    val showReview: Boolean = false,
    val currencySymbol: String = "",
    val zecAvailable: StringResource? = null,
    val zecEstimate: BigDecimal? = null,
    val isQuoting: Boolean = false,
    val onMax: (() -> Unit)? = null,
    val available: StringResource? = null,
    val amountNote: StringResource? = null,
    val isAmountInvalid: Boolean = false,
    val escrow: StringResource? = null,
    val debit: StringResource? = null,
    val railgunFee: StringResource? = null,
    val broadcasterFee: StringResource? = null,
    val receive: String? = null,
    val receiveIsEstimate: Boolean = true,
    val error: StringResource? = null,
    val progress: PrivateUsdProgressState? = null,
    val primary: ButtonState,
    val cancel: ButtonState? = null,
    val rescue: ButtonState? = null,
    val onBack: () -> Unit,
)
