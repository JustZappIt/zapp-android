// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd

import co.electriccoin.zcash.ui.design.component.zapp.ZappActionBarStyle

/** How a private USD screen sits in its frame. */
internal enum class PrivateUsdLayout(
    val actionBar: ZappActionBarStyle,
) {
    STANDARD(ZappActionBarStyle.Floating),

    /** An amount to enter, laid out like the wallet's send: a larger title, wider margins and a docked bar. */
    AMOUNT_ENTRY(ZappActionBarStyle.Docked),
}
