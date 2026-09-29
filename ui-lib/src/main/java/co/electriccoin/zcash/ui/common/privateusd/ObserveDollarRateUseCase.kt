// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.common.wallet.toZecFiatRate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.math.MathContext

/**
 * The dollar in the currency picked under You, from ZEC's price in both; the home screen loads the
 * swap catalog that prices ZEC in dollars. Null while that currency is the dollar, or either price is
 * missing: amounts then show as dollars.
 */
class ObserveDollarRateUseCase(
    private val exchangeRateRepository: ExchangeRateRepository,
    private val swapRepository: SwapRepository,
) {
    operator fun invoke(): Flow<DollarRate?> =
        combine(exchangeRateRepository.state, swapRepository.assets) { exchangeRate, assets ->
            val local = (exchangeRate as? ExchangeRateState.Data)?.currencyConversion?.toZecFiatRate()
            val zecInDollars = assets.zecAsset?.usdPrice?.takeIf { it.signum() > 0 }
            if (local == null || zecInDollars == null || local.currency == FiatCurrency.USD) {
                null
            } else {
                DollarRate(local.symbol, local.pricePerZec.divide(zecInDollars, MathContext.DECIMAL64), local.currency)
            }
        }.distinctUntilChanged()
}
