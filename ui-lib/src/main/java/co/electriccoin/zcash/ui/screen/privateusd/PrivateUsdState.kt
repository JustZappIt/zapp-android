// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.StringResource

internal data class PrivateUsdState(
    /** What can be spent, with what's in flight after a send: the figure the home screen shows. */
    val headline: StringResource,
    /** False while [headline] stands in for a balance not known yet. */
    val isHeadlineKnown: Boolean,
    /** [headline] in dollars, while the user's currency isn't the dollar. */
    val usdHeadline: StringResource?,
    val rows: List<PrivateUsdRowState>,
    val assets: List<PrivateUsdAssetState>,
    /** When the balance was updated, or that it's updating. */
    val status: StringResource?,
    val isRefreshing: Boolean,
    /** Why the last refresh left the balance as it was. */
    val refreshError: StringResource?,
    val isEmpty: Boolean,
    val conversion: PrivateUsdConversionBannerState?,
    val sending: PrivateUsdSendingState,
    val activity: List<PrivateUsdActivityState>,
    val info: PrivateUsdInfo,
    val convertButton: ButtonState,
    val onRefresh: () -> Unit,
    val onBack: () -> Unit,
)

internal data class PrivateUsdConversionBannerState(
    val title: StringResource,
    val detail: StringResource,
    val isAttention: Boolean,
    val onClick: () -> Unit,
)

internal data class PrivateUsdRowState(
    val label: StringResource,
    val amount: StringResource,
    val explanation: StringResource?,
    val isDanger: Boolean = false,
)

internal data class PrivateUsdAssetState(
    val name: StringResource,
    val amount: StringResource,
)

internal data class PrivateUsdSendingState(
    val isEnabled: Boolean,
    val onSend: () -> Unit,
    val onWithdraw: () -> Unit,
)

internal enum class PrivateUsdActivityTone { IN, OUT, NEUTRAL }

internal data class PrivateUsdActivityState(
    val key: String,
    val title: StringResource,
    val detail: StringResource,
    val amount: StringResource?,
    /** [amount] in the user's currency, while that isn't the dollar. */
    val local: StringResource?,
    val tone: PrivateUsdActivityTone,
    val onClick: (() -> Unit)?,
    /** What [onClick] does, read aloud. */
    val onClickLabel: StringResource? = null,
)
