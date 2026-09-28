package co.electriccoin.zcash.ui.screen.invest.common

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What Invest remembers for as long as the app runs, and no longer: a dismissed "Invest is more private over Tor"
 * banner stays away until the app is next started, however often Invest home is opened in between.
 */
class InvestSession {
    val isTorBannerDismissed = MutableStateFlow(false)
}
