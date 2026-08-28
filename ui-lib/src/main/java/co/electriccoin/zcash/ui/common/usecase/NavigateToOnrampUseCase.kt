package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.P2pRail
import co.electriccoin.zcash.ui.common.provider.PreferredP2pPaymentMethodProvider
import co.electriccoin.zcash.ui.screen.onramp.OnrampArgs
import xyz.justzappit.offramp.onramp.OnrampDriver

class NavigateToOnrampUseCase(
    private val preferredP2pPaymentMethodProvider: PreferredP2pPaymentMethodProvider,
    private val onrampDriver: OnrampDriver,
    private val navigationRouter: NavigationRouter,
) {
    suspend operator fun invoke() {
        // Buying runs over the p2p.me corridors only, and a Peer rail carries none of them, so a
        // cash-out selection falls back to the default corridor rather than to a currency the
        // onramp cannot serve.
        //
        // The stored selection is a Scan & Pay rail, which says only that the corridor has merchants
        // willing to *pay*. Buying is a separate market with separate merchants — Bolivia pays at any
        // size and buys only 1 USDC — so carrying the preference across requires the onramp's own
        // list, not the picker's flag. Asking the service means a corridor opening or closing does
        // not wait for an app release; if it cannot be reached the answer is empty and the user gets
        // the default corridor, which is served in every deployment.
        val buyable = onrampDriver.buyCorridors()
        val corridor =
            when (val rail = preferredP2pPaymentMethodProvider.get()) {
                is P2pRail.ScanAndPay -> rail.takeIf { it.currency in buyable } ?: P2pRail.DEFAULT
                is P2pRail.PeerCashOut -> P2pRail.DEFAULT
            }
        navigationRouter.forward(OnrampArgs(currencyCode = corridor.currency.code))
    }
}
