// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.onramp

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappInfoSheet
import xyz.justzappit.offramp.onramp.OnrampDestination

@Composable
internal fun OnrampInfoSheet(state: OnrampState, onDismiss: () -> Unit) {
    val isZcash = state.destination == OnrampDestination.ZCASH
    ZappInfoSheet(
        title = stringResource(R.string.onramp_info_title),
        steps = infoSteps(isZcash).map { stringResource(it) },
        notes =
            if (isZcash) {
                listOf(stringResource(R.string.onramp_info_zcash_note), stringResource(R.string.onramp_info_zcash_cost))
            } else {
                listOf(stringResource(R.string.onramp_info_note))
            },
        onDismiss = onDismiss,
    ) {
        OnrampDestinationInfo(state)
    }
}

private fun infoSteps(isZcash: Boolean): List<Int> =
    if (isZcash) {
        listOf(
            R.string.onramp_info_step_pay,
            R.string.onramp_info_zcash_step_settle,
            R.string.onramp_info_zcash_step_convert,
        )
    } else {
        listOf(
            R.string.onramp_info_step_pay,
            R.string.onramp_info_step_confirm,
            R.string.onramp_info_step_settle,
        )
    }
