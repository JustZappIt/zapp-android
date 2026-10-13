// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.refunds

import co.electriccoin.zcash.ui.common.security.PinVerifyState
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.StringResource

internal data class PrivateUsdRefundsState(
    val refunds: List<PrivateUsdRefundState> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: StringResource? = null,
    val isBackEnabled: Boolean = true,
    val pinVerify: PinVerifyState? = null,
    val onRefresh: () -> Unit,
    val onBack: () -> Unit,
)

internal data class PrivateUsdRefundState(
    val index: Int,
    val date: StringResource,
    val amount: StringResource?,
    val status: StringResource,
    val isProblem: Boolean,
    val recover: ButtonState?,
    val error: StringResource?,
)
