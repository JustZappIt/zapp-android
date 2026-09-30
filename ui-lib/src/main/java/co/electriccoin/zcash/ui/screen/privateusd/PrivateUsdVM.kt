// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.privateusd.LocalCurrency
import co.electriccoin.zcash.ui.common.privateusd.ObserveLocalCurrencyUseCase
import co.electriccoin.zcash.ui.common.privateusd.ObservePrivateUsdActivityUseCase
import co.electriccoin.zcash.ui.common.privateusd.ObservePrivateUsdConversionUseCase
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdActivityData
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceRepository
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceState
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalances
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdConversion
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSenders
import co.electriccoin.zcash.ui.common.privateusd.dollars
import co.electriccoin.zcash.ui.common.privateusd.toDecimal
import co.electriccoin.zcash.ui.common.usecase.NavigateBackToPayUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.asPrivacySensitive
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.ExternalUrl
import co.electriccoin.zcash.ui.screen.privateusd.convert.PrivateUsdConvertArgs
import co.electriccoin.zcash.ui.screen.privateusd.send.PrivateUsdSendArgs
import co.electriccoin.zcash.ui.screen.privateusd.send.PrivateUsdSendMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Duration.Companion.seconds

class PrivateUsdVM(
    private val balanceRepository: PrivateUsdBalanceRepository,
    atomicSwapRepository: AtomicSwapRepository,
    private val senders: PrivateUsdSenders,
    observeConversion: ObservePrivateUsdConversionUseCase,
    observeActivity: ObservePrivateUsdActivityUseCase,
    observeLocalCurrency: ObserveLocalCurrencyUseCase,
    private val activityMapper: PrivateUsdActivityMapper,
    private val navigateBackToPay: NavigateBackToPayUseCase,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val deployment = atomicSwapRepository.requireDeployment()

    internal val state: StateFlow<PrivateUsdState> =
        combine(
            balanceRepository.observe(),
            observeConversion(),
            observeActivity(),
            observeLocalCurrency(),
            ::createState,
        ).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = createState(balanceRepository.state.value, null, emptyList(), LocalCurrency.DOLLAR),
        )

    init {
        balanceRepository.refresh(maxAge = REFRESH_AFTER)
        atomicSwapRepository.findMissingPayouts()
    }

    private fun createState(
        balance: PrivateUsdBalanceState,
        conversion: PrivateUsdConversion?,
        activity: List<PrivateUsdActivityData>,
        currency: LocalCurrency,
    ): PrivateUsdState {
        val balances = balance.balances
        return PrivateUsdState(
            headline = balances?.headline(currency)?.asPrivacySensitive() ?: balance.placeholder(),
            isHeadlineKnown = balances != null,
            usdHeadline =
                balances?.takeUnless { currency.isDollar }?.let { dollars(it.spendable).asPrivacySensitive() },
            rows = balances?.let { rows(it, currency) }.orEmpty(),
            assets = balances?.let { assets(it, currency) }.orEmpty(),
            status = status(balance),
            isRefreshing = balance.isRefreshing,
            refreshError = refreshError(balance),
            isEmpty = balances != null && balances.total.signum() == 0 && conversion == null,
            conversion = conversion?.let { it.banner { onOpenConversion(it) } },
            sending =
                PrivateUsdSendingState(
                    isEnabled =
                        senders.current != null &&
                            balances?.assets?.any { it.token.isDollar && it.available.signum() > 0 } == true,
                    onSend = { navigationRouter.forward(PrivateUsdSendArgs(PrivateUsdSendMode.PRIVATE)) },
                    onWithdraw = { navigationRouter.forward(PrivateUsdSendArgs(PrivateUsdSendMode.WITHDRAW)) },
                ),
            activity =
                activity.mapNotNull {
                    activityMapper.createState(
                        data = it,
                        deployment = deployment,
                        conversion = conversion,
                        currency = currency,
                        onOpenConversion = ::onOpenConversion,
                        onOpenUrl = { url -> navigationRouter.forward(ExternalUrl(url)) },
                    )
                },
            info = info(currency),
            convertButton =
                ButtonState(stringRes(R.string.private_usd_action_convert)) {
                    navigationRouter.forward(conversion?.progressArgs ?: PrivateUsdConvertArgs)
                },
            onRefresh = { balanceRepository.refresh() },
            onBack = navigateBackToPay::invoke,
        )
    }

    private fun onOpenConversion(conversion: PrivateUsdConversion) = navigationRouter.forward(conversion.progressArgs)

    private fun status(balance: PrivateUsdBalanceState): StringResource? =
        when {
            balance.isRefreshing && balance.balances == null -> stringRes(R.string.private_usd_first_load)
            balance.isRefreshing -> stringRes(R.string.private_usd_updating)
            else -> balance.updatedAt?.let { stringRes(R.string.private_usd_updated, dateTime(it)) }
        }

    private fun refreshError(balance: PrivateUsdBalanceState): StringResource? =
        when {
            !balance.refreshFailed -> null
            balance.balances == null -> stringRes(R.string.private_usd_load_failed)
            else -> stringRes(R.string.private_usd_refresh_failed)
        }

    private fun rows(
        balances: PrivateUsdBalances,
        currency: LocalCurrency
    ): List<PrivateUsdRowState> =
        listOfNotNull(
            PrivateUsdRowState(
                label = stringRes(R.string.private_usd_row_available),
                amount = currency.format(balances.available).asPrivacySensitive(),
                explanation = null,
            ),
            balances.processing.takeIf { it.signum() > 0 }?.let {
                PrivateUsdRowState(
                    label = stringRes(R.string.private_usd_row_processing),
                    amount = currency.format(it).asPrivacySensitive(),
                    explanation = stringRes(R.string.private_usd_row_processing_info),
                )
            },
            balances.arriving.takeIf { it.signum() > 0 }?.let {
                PrivateUsdRowState(
                    label = stringRes(R.string.private_usd_row_arriving),
                    amount = currency.format(it).asPrivacySensitive(),
                    explanation = stringRes(R.string.private_usd_row_arriving_info, deployment.screeningTime.about()),
                )
            },
            balances.blocked.takeIf { it.signum() > 0 }?.let {
                PrivateUsdRowState(
                    label = stringRes(R.string.private_usd_row_blocked),
                    amount = currency.format(it).asPrivacySensitive(),
                    explanation = stringRes(R.string.private_usd_row_blocked_info),
                    isDanger = true,
                )
            },
        )

    // Dollars only, and only worth listing when there's more than one kind.
    private fun assets(
        balances: PrivateUsdBalances,
        currency: LocalCurrency
    ): List<PrivateUsdAssetState> {
        val held =
            balances.assets
                .filter { it.token.isDollar }
                .map { it to (it.available + it.arriving + it.blocked + it.processing) }
                .filter { (_, amount) -> amount.signum() > 0 }
        return if (held.size > 1) {
            held.map { (asset, amount) ->
                PrivateUsdAssetState(
                    name = stringRes(asset.token.name),
                    amount = currency.format(amount.toDecimal(asset.token.decimals)).asPrivacySensitive(),
                )
            }
        } else {
            emptyList()
        }
    }

    private fun info(currency: LocalCurrency) =
        PrivateUsdInfo(
            title = stringRes(R.string.private_usd_info_title),
            steps =
                listOf(
                    stringRes(R.string.private_usd_info_step_convert),
                    stringRes(R.string.private_usd_info_step_screen, deployment.screeningTime.about()),
                    stringRes(R.string.private_usd_info_send),
                    stringRes(R.string.private_usd_info_withdraw),
                ),
            notes =
                listOfNotNull(
                    stringRes(R.string.private_usd_info_note_private),
                    stringRes(R.string.private_usd_info_note_currency).takeUnless { currency.isDollar },
                ),
        )

    private companion object {
        val REFRESH_AFTER = 30.seconds
    }
}
