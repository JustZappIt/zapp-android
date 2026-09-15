package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.onramp.OnrampArgs
import kotlinx.coroutines.CancellationException
import xyz.justzappit.offramp.account.SmartOfframpAccountProvider
import xyz.justzappit.offramp.onramp.OnrampRouteReader
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6

class NavigateToOnrampUseCase(
    private val resolveBuyCorridor: ResolveBuyCorridorUseCase,
    private val accountProvider: SmartOfframpAccountProvider,
    private val routeReader: OnrampRouteReader,
    private val navigateToReputation: NavigateToReputationUseCase,
    private val navigationRouter: NavigationRouter,
) {
    suspend operator fun invoke() {
        val corridor = resolveBuyCorridor()
        if (canBuy(corridor)) {
            navigationRouter.forward(OnrampArgs(currencyCode = corridor.code))
        } else {
            navigateToReputation(corridor)
        }
    }

    /**
     * The order is placed by the user's own smart account, and it is refused from an address
     * that holds neither reputation on the Diamond nor a passed selfie check on the integrator —
     * so a wallet that cannot buy yet is sent to Reputation rather than to an amount field it
     * cannot submit. The same reader sizes the amount screen, so the two never disagree.
     *
     * ☠ This gate applies to everyone. It used to be skipped on the custodial route, which placed
     * every order from the operator's own reputation-bearing account and so gated nothing; with
     * that route gone, an existing user with neither must verify before they can buy again.
     *
     * An unreadable chain lets them through: the failure is ours, and the onramp screen quotes
     * against the same limit before it takes an amount.
     */
    private suspend fun canBuy(corridor: CurrencyCode): Boolean =
        try {
            routeReader.read(accountProvider.resolve().address, corridor).max > Usdc6.ZERO
        } catch (e: CancellationException) {
            throw e
        } catch (
            // Broad on purpose: any read failure means the same thing to the user, and the
            // reason belongs in the log rather than on screen.
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Twig.warn(e) { "Limit read failed before onramp; letting the buy proceed" }
            true
        }
}
