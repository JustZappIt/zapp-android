package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.onramp.OnrampArgs
import co.electriccoin.zcash.ui.screen.reputation.ReputationArgs
import kotlinx.coroutines.CancellationException
import xyz.justzappit.offramp.account.SmartOfframpAccountProvider
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.reputation.ReputationReader

class NavigateToOnrampUseCase(
    private val resolveBuyCorridor: ResolveBuyCorridorUseCase,
    private val accountProvider: SmartOfframpAccountProvider,
    private val reputationReader: ReputationReader,
    private val navigationRouter: NavigationRouter,
) {
    suspend operator fun invoke() {
        val corridor = resolveBuyCorridor()
        navigationRouter.forward(
            if (canBuy(corridor)) {
                OnrampArgs(currencyCode = corridor.code)
            } else {
                ReputationArgs(currencyCode = corridor.code)
            },
        )
    }

    /**
     * The order is placed by the user's own smart account, and the Diamond refuses a BUY from an
     * address with no reputation — so a wallet that cannot buy yet is sent to Reputation rather
     * than to an amount field it cannot submit.
     *
     * ☠ This gate now applies to everyone. It used to be skipped on the custodial route, which
     * placed every order from the operator's own reputation-bearing account and so gated nothing;
     * with that route gone, an existing user at 0 RP must verify before they can buy again.
     *
     * An unreadable chain lets them through: the failure is ours, and the onramp screen quotes
     * against the same limit before it takes an amount.
     */
    private suspend fun canBuy(corridor: CurrencyCode): Boolean =
        try {
            reputationReader.read(accountProvider.resolve().address, corridor).canBuy
        } catch (e: CancellationException) {
            throw e
        } catch (
            // Broad on purpose: any read failure means the same thing to the user, and the
            // reason belongs in the log rather than on screen.
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Twig.warn(e) { "Reputation read failed before onramp; letting the buy proceed" }
            true
        }
}
