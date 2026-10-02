package co.electriccoin.zcash.ui.screen.invest.common

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettings
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.settings.InvestSettingsArgs

/**
 * Sell-only mode (founder decision, 2026-09-28): set up once, but the saved country no longer allows buying
 * (the user moved, or is in a restricted country without the attestation). Buy is off everywhere and Sell stays
 * open, so nothing held is trapped.
 */
internal val InvestSettings.isSellOnly: Boolean get() = setupComplete && !isAvailable

/** "Invest isn't available in Canada. You can still sell what you hold.", linking to Settings › Invest. */
internal fun InvestSettings.sellOnlyNotice(navigationRouter: NavigationRouter): InvestTradeInProgressState? =
    countryCode?.takeUnless { isAvailable }?.let { code ->
        InvestTradeInProgressState(
            text = stringRes(R.string.invest_sell_only_banner, InvestFormat.countryName(code)),
            actionLabel = stringRes(R.string.invest_sell_only_change),
            onOpen = { navigationRouter.forward(InvestSettingsArgs) },
        )
    }
