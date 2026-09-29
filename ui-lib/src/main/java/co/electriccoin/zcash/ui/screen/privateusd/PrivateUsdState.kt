// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd

import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.privateusd.widget.PrivateUsdConversionBannerState

internal data class PrivateUsdState(
    /** Null until a first balance is known. */
    val total: StringResource?,
    val rows: List<PrivateUsdRowState>,
    val assets: List<PrivateUsdAssetState>,
    /** When the balance was updated. */
    val status: StringResource?,
    val isRefreshing: Boolean,
    val refreshFailed: Boolean,
    val isEmpty: Boolean,
    val conversion: PrivateUsdConversionBannerState?,
    /** Null while this build can't send. */
    val sending: PrivateUsdSendingState?,
    val activity: List<PrivateUsdActivityState>,
    val info: PrivateUsdInfo,
    val onConvert: () -> Unit,
    val onRefresh: () -> Unit,
    val onBack: () -> Unit,
    val usdTotal: StringResource? = null,
)

internal data class PrivateUsdRowState(
    val label: StringResource,
    val amount: StringResource,
    val explanation: StringResource?,
    val isDanger: Boolean = false,
)

internal data class PrivateUsdAssetState(
    val name: String,
    val amount: StringResource,
)

internal data class PrivateUsdSendingState(
    val isEnabled: Boolean,
    val onSend: () -> Unit,
    val onWithdraw: () -> Unit,
)
