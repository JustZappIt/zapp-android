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
    /** What holds the conversion up, while something does. */
    val problem: PrivateUsdProblemState?,
    /** Why what the user asked for last didn't happen. */
    val error: StringResource?,
    val callOff: ButtonState?,
    val showsBackgroundNote: Boolean,
    val primaryButton: ButtonState?,
    val info: PrivateUsdInfo,
    val onBack: () -> Unit,
    /** False while a step the user authorized runs. */
    val isBackEnabled: Boolean = true,
)

internal data class PrivateUsdResultState(
    val title: StringResource,
    val body: StringResource,
    val isSuccess: Boolean,
)

internal data class PrivateUsdProblemState(
    val message: StringResource,
    /** Tries again at once; null where the screen's own action is the way to. */
    val onRetry: (() -> Unit)?,
)
