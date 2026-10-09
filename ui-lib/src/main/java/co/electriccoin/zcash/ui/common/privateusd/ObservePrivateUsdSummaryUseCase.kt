// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Private USD at a glance, as the wallet's screens show it beside the ZEC balance. */
data class PrivateUsdSummary(
    val balance: PrivateUsdBalanceState,
    val conversion: PrivateUsdConversion?,
    val currency: LocalCurrency,
) {
    val isBlocked: Boolean get() = balance.balances?.isBlocked == true
}

/** [PrivateUsdSummary] while Private USD is available, and null while it isn't. */
class ObservePrivateUsdSummaryUseCase(
    private val observePrivateUsdAvailable: ObservePrivateUsdAvailableUseCase,
    private val observeLocalCurrency: ObserveLocalCurrencyUseCase,
    // Built only once Private USD is available: building them sets up the conversions and their stores.
    private val balanceRepository: Lazy<PrivateUsdBalanceRepository>,
    private val observeConversion: Lazy<ObservePrivateUsdConversionUseCase>,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<PrivateUsdSummary?> =
        observePrivateUsdAvailable().flatMapLatest { isAvailable ->
            if (isAvailable) {
                combine(
                    balanceRepository.value.observeIfUsed(),
                    observeConversion.value(),
                    observeLocalCurrency(),
                    ::PrivateUsdSummary,
                )
            } else {
                flowOf(null)
            }
        }
}
