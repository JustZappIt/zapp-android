// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.widget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.privateusd.ObservePrivateUsdSummaryUseCase
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdConversion
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSummary
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdArgs
import co.electriccoin.zcash.ui.screen.privateusd.arrivingTag
import co.electriccoin.zcash.ui.screen.privateusd.banner
import co.electriccoin.zcash.ui.screen.privateusd.convert.PrivateUsdConvertArgs
import co.electriccoin.zcash.ui.screen.privateusd.headline
import co.electriccoin.zcash.ui.screen.privateusd.placeholder
import co.electriccoin.zcash.ui.screen.privateusd.progressArgs
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class PrivateUsdWidgetVM(
    observePrivateUsdSummary: ObservePrivateUsdSummaryUseCase,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    // Read when tapped, so every state keeps the same callbacks and the balance card doesn't recompose for them.
    @Volatile
    private var conversion: PrivateUsdConversion? = null
    private val onClick: () -> Unit = { navigationRouter.forward(PrivateUsdArgs) }
    private val onConvertClick: () -> Unit = {
        navigationRouter.forward(conversion?.progressArgs ?: PrivateUsdConvertArgs)
    }
    private val onOpenConversion: () -> Unit = { conversion?.let { navigationRouter.forward(it.progressArgs) } }

    internal val state: StateFlow<PrivateUsdWidgetState?> =
        observePrivateUsdSummary()
            .map { it?.let(::createState) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = null,
            )

    private fun createState(summary: PrivateUsdSummary): PrivateUsdWidgetState {
        val balances = summary.balance.balances
        conversion = summary.conversion
        return PrivateUsdWidgetState(
            balance = balances?.headline(summary.currency) ?: summary.balance.placeholder(),
            arriving = balances?.arrivingTag(summary.currency),
            arrivingDescription =
                balances?.arriving?.takeIf { it.signum() > 0 }?.let {
                    stringRes(R.string.private_usd_home_arriving_description, summary.currency.format(it))
                },
            isBlocked = summary.isBlocked,
            conversion = summary.conversion?.banner(onOpenConversion),
            onClick = onClick,
            onConvertClick = onConvertClick,
        )
    }
}
