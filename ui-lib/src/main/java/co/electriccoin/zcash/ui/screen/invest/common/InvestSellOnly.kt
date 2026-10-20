package co.electriccoin.zcash.ui.screen.invest.common

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettings
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.settings.InvestSettingsArgs

/** Set up, but the saved country no longer allows buying: Sell stays open so nothing held is trapped. */
internal val InvestSettings.isSellOnly: Boolean get() = setupComplete && !isAvailable

internal fun InvestSettings.sellOnlyNotice(navigationRouter: NavigationRouter): InvestTradeInProgressState? =
    countryCode?.takeUnless { isAvailable }?.let { code ->
        InvestTradeInProgressState(
            text = stringRes(R.string.invest_sell_only_banner, InvestFormat.countryName(code)),
            actionLabel = stringRes(R.string.invest_sell_only_change),
            onOpen = { navigationRouter.forward(InvestSettingsArgs) },
        )
    }
