// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.progress

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappStep
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdInfo

internal data class PrivateUsdProgressState(
    val amounts: StringResource?,
    /** Set once the swap is over. */
    val result: PrivateUsdResultState?,
    val steps: List<ZappStep>,
    val note: StringResource?,
    val callOff: ButtonState?,
    val showsBackgroundNote: Boolean,
    val primaryButton: ButtonState?,
    val info: PrivateUsdInfo,
    val onBack: () -> Unit,
)

internal data class PrivateUsdResultState(
    val title: StringResource,
    val body: StringResource,
    val isSuccess: Boolean,
)
