// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.widget

import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdConversionBannerState

internal data class PrivateUsdWidgetState(
    /** Shown as is: the balance card hides it with the rest. */
    val balance: StringResource,
    val arriving: StringResource?,
    /** [arriving] as it reads aloud. */
    val arrivingDescription: StringResource? = null,
    /** Screening refused some of it. */
    val isBlocked: Boolean,
    val conversion: PrivateUsdConversionBannerState?,
    val onClick: () -> Unit,
    val onConvertClick: () -> Unit,
)
