package co.electriccoin.zcash.ui.screen.invest.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What Invest remembers until the app is closed: a dismissed Tor banner. */
class InvestSession {
    private val torBannerDismissed = MutableStateFlow(false)
    val isTorBannerDismissed: StateFlow<Boolean> = torBannerDismissed.asStateFlow()

    fun dismissTorBanner() {
        torBannerDismissed.value = true
    }
}
