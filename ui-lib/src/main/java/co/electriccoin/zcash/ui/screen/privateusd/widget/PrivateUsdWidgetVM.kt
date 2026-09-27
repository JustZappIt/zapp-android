// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.widget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapState
import co.electriccoin.zcash.ui.common.privateusd.ObservePrivateUsdAvailableUseCase
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceRepository
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceState
import co.electriccoin.zcash.ui.common.privateusd.dollars
import co.electriccoin.zcash.ui.design.util.asPrivacySensitive
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdArgs
import co.electriccoin.zcash.ui.screen.privateusd.convert.PrivateUsdConvertArgs
import co.electriccoin.zcash.ui.screen.privateusd.progress.PrivateUsdProgressArgs
import co.electriccoin.zcash.ui.screen.privateusd.stageDetail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

class PrivateUsdWidgetVM(
    observePrivateUsdAvailable: ObservePrivateUsdAvailableUseCase,
    balanceRepository: PrivateUsdBalanceRepository,
    private val atomicSwapRepository: AtomicSwapRepository,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    @OptIn(ExperimentalCoroutinesApi::class)
    internal val state: StateFlow<PrivateUsdWidgetState?> =
        observePrivateUsdAvailable()
            .flatMapLatest { isAvailable ->
                if (isAvailable) {
                    combine(balanceRepository.observe(), atomicSwapRepository.state, ::createState)
                } else {
                    flowOf(null)
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = null,
            )

    private fun createState(
        balance: PrivateUsdBalanceState,
        swap: AtomicSwapState
    ): PrivateUsdWidgetState {
        val balances = balance.balances
        return PrivateUsdWidgetState(
            balance = balances?.let { dollars(it.available + it.processing).asPrivacySensitive() },
            arriving =
                balances?.arriving?.takeIf { it.signum() > 0 }?.let {
                    stringRes(R.string.private_usd_home_arriving, dollars(it).asPrivacySensitive())
                },
            blocked =
                balances?.blocked?.takeIf { it.signum() > 0 }?.let {
                    stringRes(R.string.private_usd_home_blocked, dollars(it).asPrivacySensitive())
                },
            conversion =
                if (swap.isUnderWay) {
                    PrivateUsdConversionBannerState(
                        title =
                            stringRes(
                                if (swap.problem != null) {
                                    R.string.private_usd_banner_attention_title
                                } else {
                                    R.string.private_usd_banner_title
                                }
                            ),
                        detail = swap.stageDetail(atomicSwapRepository.deployment?.makerConfirmations),
                        isAttention = swap.problem != null,
                        onClick = { navigationRouter.forward(PrivateUsdProgressArgs) },
                    )
                } else {
                    null
                },
            onClick = { navigationRouter.forward(PrivateUsdArgs) },
            onConvertClick = {
                navigationRouter.forward(if (swap.isUnderWay) PrivateUsdProgressArgs else PrivateUsdConvertArgs)
            },
        )
    }
}
