// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.common.provider.PreferredFiatProvider
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.SwapAssetsData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.common.wallet.toZecFiatRate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.math.MathContext

/** The currency picked under You, at ZEC's price in it over its dollar price; dollars until both are known. */
class ObserveLocalCurrencyUseCase(
    private val preferredFiat: PreferredFiatProvider,
    private val exchangeRateRepository: ExchangeRateRepository,
    private val swapRepository: SwapRepository,
) {
    operator fun invoke(): Flow<LocalCurrency> =
        combine(preferredFiat.observe(), exchangeRateRepository.state, swapRepository.assets, ::localCurrency)
            .distinctUntilChanged()

    private fun localCurrency(
        preferred: FiatCurrency?,
        exchangeRate: ExchangeRateState,
        assets: SwapAssetsData,
    ): LocalCurrency {
        val local =
            (exchangeRate as? ExchangeRateState.Data)
                ?.currencyConversion
                ?.toZecFiatRate()
                ?.takeIf { it.currency == preferred && it.currency != FiatCurrency.USD }
        val zecInDollars = assets.zecAsset?.usdPrice?.takeIf { it.signum() > 0 }
        return if (local == null || zecInDollars == null) {
            LocalCurrency.DOLLAR
        } else {
            LocalCurrency(local.currency, local.symbol, local.pricePerZec.divide(zecInDollars, MathContext.DECIMAL64))
        }
    }
}
