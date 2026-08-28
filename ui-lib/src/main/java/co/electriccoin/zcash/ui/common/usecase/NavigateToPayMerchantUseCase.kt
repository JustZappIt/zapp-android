package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.P2pRail
import co.electriccoin.zcash.ui.common.provider.PreferredP2pPaymentMethodProvider
import co.electriccoin.zcash.ui.screen.settings.p2p.P2pPaymentMethod
import co.electriccoin.zcash.ui.screen.swap.UpiOfframpArgs
import co.electriccoin.zcash.ui.screen.swap.peer.PeerCashOutArgs
import xyz.justzappit.offramp.peer.PeerConfigProvider

class NavigateToPayMerchantUseCase(
    private val preferredP2pPaymentMethodProvider: PreferredP2pPaymentMethodProvider,
    private val peerConfigProvider: PeerConfigProvider,
    private val navigationRouter: NavigationRouter,
) {
    suspend operator fun invoke() {
        // A Peer rail can outlive the build that offered it (a flavour switch leaves the stored
        // selection behind), so an unavailable one falls back rather than opening a dead flow.
        when (val rail = preferredP2pPaymentMethodProvider.get()) {
            is P2pRail.ScanAndPay -> {
                // The picker refuses an unavailable rail, but a stored selection predates the flag:
                // a corridor withdrawn after the user chose it would otherwise still open here.
                val currency =
                    if (P2pPaymentMethod.fromCurrency(rail.currency).available) {
                        rail.currency
                    } else {
                        P2pRail.DEFAULT.currency
                    }
                navigationRouter.forward(UpiOfframpArgs(currencyCode = currency.code))
            }

            is P2pRail.PeerCashOut -> {
                if (peerConfigProvider.isAvailable) {
                    navigationRouter.forward(PeerCashOutArgs(platformWireName = rail.platform.wireName))
                } else {
                    navigationRouter.forward(UpiOfframpArgs(currencyCode = P2pRail.DEFAULT.currency.code))
                }
            }
        }
    }
}
