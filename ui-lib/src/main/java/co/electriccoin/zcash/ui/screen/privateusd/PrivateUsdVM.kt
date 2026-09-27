// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapState
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceRepository
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceState
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalances
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSendLog
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSenders
import co.electriccoin.zcash.ui.common.privateusd.dollars
import co.electriccoin.zcash.ui.common.privateusd.tokenAmount
import co.electriccoin.zcash.ui.design.util.asPrivacySensitive
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.ExternalUrl
import co.electriccoin.zcash.ui.screen.privateusd.convert.PrivateUsdConvertArgs
import co.electriccoin.zcash.ui.screen.privateusd.progress.PrivateUsdProgressArgs
import co.electriccoin.zcash.ui.screen.privateusd.send.PrivateUsdSendArgs
import co.electriccoin.zcash.ui.screen.privateusd.widget.PrivateUsdConversionBannerState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class PrivateUsdVM(
    private val balanceRepository: PrivateUsdBalanceRepository,
    private val atomicSwapRepository: AtomicSwapRepository,
    private val senders: PrivateUsdSenders,
    sendLog: PrivateUsdSendLog,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val activity =
        atomicSwapRepository.deployment?.let { deployment ->
            PrivateUsdActivity(
                deployment = deployment,
                onOpenConversion = { navigationRouter.forward(PrivateUsdProgressArgs) },
                onOpenUrl = { navigationRouter.forward(ExternalUrl(it)) },
            )
        }

    internal val state: StateFlow<PrivateUsdState> =
        combine(
            balanceRepository.observe(),
            atomicSwapRepository.state,
            atomicSwapRepository.history,
            sendLog.observe,
        ) { balance, swap, swaps, sends ->
            createState(balance, swap, activity?.of(swaps, sends, swap).orEmpty())
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = createState(balanceRepository.state.value, atomicSwapRepository.state.value, emptyList()),
        )

    init {
        balanceRepository.refresh(maxAge = REFRESH_AFTER)
    }

    private fun createState(
        balance: PrivateUsdBalanceState,
        swap: AtomicSwapState,
        activity: List<PrivateUsdActivityState>,
    ): PrivateUsdState {
        val balances = balance.balances
        return PrivateUsdState(
            total = balances?.let { dollars(it.total).asPrivacySensitive() },
            rows = balances?.let(::rows).orEmpty(),
            assets = balances?.let(::assets).orEmpty(),
            status =
                when {
                    balance.isRefreshing && balances == null -> stringRes(R.string.private_usd_first_load)
                    balance.isRefreshing -> stringRes(R.string.private_usd_updating)
                    else -> balance.updatedAt?.let { stringRes(R.string.private_usd_updated, time(it)) }
                },
            isRefreshing = balance.isRefreshing,
            refreshFailed = balance.refreshFailed,
            isEmpty = balances != null && balances.total.signum() == 0 && !swap.isUnderWay,
            conversion =
                if (swap.isUnderWay) {
                    PrivateUsdConversionBannerState(
                        title = stringRes(R.string.private_usd_banner_title),
                        detail = swap.stageDetail(atomicSwapRepository.deployment?.makerConfirmations),
                        isAttention = swap.problem != null,
                        onClick = { navigationRouter.forward(PrivateUsdProgressArgs) },
                    )
                } else {
                    null
                },
            sending =
                senders.current?.let {
                    PrivateUsdSendingState(
                        isEnabled = balances?.assets?.any { it.token.isDollar && it.available.signum() > 0 } == true,
                        onSend = { navigationRouter.forward(PrivateUsdSendArgs(withdraw = false)) },
                        onWithdraw = { navigationRouter.forward(PrivateUsdSendArgs(withdraw = true)) },
                    )
                },
            activity = activity,
            onConvert = {
                navigationRouter.forward(if (swap.isUnderWay) PrivateUsdProgressArgs else PrivateUsdConvertArgs)
            },
            onRefresh = { balanceRepository.refresh() },
            onBack = navigationRouter::back,
        )
    }

    private fun rows(balances: PrivateUsdBalances): List<PrivateUsdRowState> {
        val screening = atomicSwapRepository.deployment?.screeningTime ?: SCREENING_FALLBACK
        return listOfNotNull(
            PrivateUsdRowState(
                label = stringRes(R.string.private_usd_row_available),
                amount = dollars(balances.available).asPrivacySensitive(),
                explanation = null,
            ),
            balances.arriving.takeIf { it.signum() > 0 }?.let {
                PrivateUsdRowState(
                    label = stringRes(R.string.private_usd_row_arriving),
                    amount = dollars(it).asPrivacySensitive(),
                    explanation = stringRes(R.string.private_usd_row_arriving_info, screening.about()),
                )
            },
            balances.processing.takeIf { it.signum() > 0 }?.let {
                PrivateUsdRowState(
                    label = stringRes(R.string.private_usd_row_processing),
                    amount = dollars(it).asPrivacySensitive(),
                    explanation = stringRes(R.string.private_usd_row_processing_info),
                )
            },
            balances.blocked.takeIf { it.signum() > 0 }?.let {
                PrivateUsdRowState(
                    label = stringRes(R.string.private_usd_row_blocked),
                    amount = dollars(it).asPrivacySensitive(),
                    explanation = stringRes(R.string.private_usd_row_blocked_info),
                    isDanger = true,
                )
            },
        )
    }

    // Dollars only, and only worth listing when there's more than one kind.
    private fun assets(balances: PrivateUsdBalances): List<PrivateUsdAssetState> {
        val held =
            balances.assets.filter {
                it.token.isDollar && (it.available + it.arriving + it.blocked + it.processing).signum() > 0
            }
        return if (held.size > 1) {
            held.map {
                PrivateUsdAssetState(
                    name = it.token.name,
                    amount =
                        tokenAmount(it.available + it.arriving + it.blocked + it.processing, it.token)
                            .asPrivacySensitive(),
                )
            }
        } else {
            emptyList()
        }
    }

    private fun time(instant: Instant): String =
        DateTimeFormatter
            .ofLocalizedTime(FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault())
            .format(java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()))

    private companion object {
        val REFRESH_AFTER = 30.seconds
        val SCREENING_FALLBACK = 60.minutes
    }
}
