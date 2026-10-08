// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.compose

import co.electriccoin.zcash.ui.design.util.StringResource

/**
 * What a money flow shows in place of its form once [co.electriccoin.zcash.ui.common.usecase.Funding.EMPTY]:
 * why there is nothing to do yet, and the one action that fixes it.
 */
data class AddFundsPanelState(
    val body: StringResource,
    val onAddFunds: () -> Unit,
)
