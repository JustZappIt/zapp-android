package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey

interface IsPortfolioChartEnabledProvider : BooleanStorageProvider

class IsPortfolioChartEnabledProviderImpl(
    override val preferenceHolder: StandardPreferenceProvider,
) : BaseBooleanStorageProvider(
        // Keep the original persisted key so existing chart opt-outs survive the fiat-aware rename.
        key = PreferenceKey(LEGACY_PREFERENCE_KEY),
        default = true,
    ),
    IsPortfolioChartEnabledProvider

private const val LEGACY_PREFERENCE_KEY = "USD_PORTFOLIO_CHART_ENABLED"
