// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.widget

import co.electriccoin.zcash.ui.design.util.StringResource

internal data class PrivateUsdWidgetState(
    /** Null until a first balance is known. */
    val balance: StringResource?,
    val arriving: StringResource?,
    /** Screening refused some of it. */
    val isBlocked: Boolean,
    val conversion: PrivateUsdConversionBannerState?,
    val onClick: () -> Unit,
    val onConvertClick: () -> Unit,
)

internal data class PrivateUsdConversionBannerState(
    val title: StringResource,
    val detail: StringResource,
    val isAttention: Boolean,
    val onClick: () -> Unit,
)
