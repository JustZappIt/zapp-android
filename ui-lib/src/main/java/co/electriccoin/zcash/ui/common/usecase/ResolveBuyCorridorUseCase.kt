package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.model.P2pRail
import co.electriccoin.zcash.ui.common.provider.PreferredP2pPaymentMethodProvider
import xyz.justzappit.offramp.onramp.OnrampDriver
import xyz.justzappit.offramp.p2p.CurrencyCode

/**
 * The corridor a buy would use.
 *
 * Buying runs over the p2p.me corridors only, and a Peer rail carries none of them, so a cash-out
 * selection falls back to the default corridor rather than to a currency the onramp cannot serve.
 *
 * The stored selection is a Scan & Pay rail, which says only that the corridor has merchants
 * willing to *pay*. Buying is a separate market with separate merchants — Bolivia pays at any size
 * and buys only 1 USDC — so carrying the preference across requires the onramp's own list, not the
 * picker's flag. Asking the driver means a corridor opening or closing does not wait for an app
 * release; if it cannot be reached the answer is empty and the user gets the default corridor,
 * which is served in every deployment.
 */
class ResolveBuyCorridorUseCase(
    private val preferredP2pPaymentMethodProvider: PreferredP2pPaymentMethodProvider,
    private val onrampDriver: OnrampDriver,
) {
    suspend operator fun invoke(): CurrencyCode {
        val buyable = onrampDriver.buyCorridors()
        val rail =
            when (val selected = preferredP2pPaymentMethodProvider.get()) {
                is P2pRail.ScanAndPay -> selected.takeIf { it.currency in buyable } ?: P2pRail.DEFAULT
                is P2pRail.PeerCashOut -> P2pRail.DEFAULT
            }
        return rail.currency
    }
}
