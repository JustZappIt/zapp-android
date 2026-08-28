package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.P2pRail
import co.electriccoin.zcash.ui.common.provider.PreferredP2pPaymentMethodProvider
import co.electriccoin.zcash.ui.screen.onramp.OnrampArgs
import co.electriccoin.zcash.ui.screen.settings.p2p.P2pPaymentMethod

class NavigateToOnrampUseCase(
    private val preferredP2pPaymentMethodProvider: PreferredP2pPaymentMethodProvider,
    private val navigationRouter: NavigationRouter,
) {
    suspend operator fun invoke() {
        // Buying runs over the p2p.me corridors only, and a Peer rail carries none of them, so a
        // cash-out selection falls back to the default corridor rather than to a currency the
        // onramp cannot serve.
        val corridor =
            when (val rail = preferredP2pPaymentMethodProvider.get()) {
                // A withdrawn corridor can still be the stored selection, so availability is
                // re-checked here rather than trusted from whenever the user picked it.
                is P2pRail.ScanAndPay -> {
                    if (P2pPaymentMethod.fromCurrency(rail.currency).available) rail else P2pRail.DEFAULT
                }

                is P2pRail.PeerCashOut -> {
                    P2pRail.DEFAULT
                }
            }
        navigationRouter.forward(OnrampArgs(currencyCode = corridor.currency.code))
    }
}
