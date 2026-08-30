package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.reputation.ReputationArgs
import xyz.justzappit.offramp.p2p.CurrencyCode

/**
 * Opens the reputation screen for the corridor the user would actually buy in. Limits are
 * per-currency and differ several-fold between them, so the corridor is passed in by the caller
 * that already resolved it rather than defaulted inside the screen — and resolving it again here
 * would cost a second `buyCorridors()` round trip for an answer the caller is holding.
 */
class NavigateToReputationUseCase(
    private val navigationRouter: NavigationRouter,
) {
    operator fun invoke(corridor: CurrencyCode) {
        navigationRouter.forward(ReputationArgs(currency = corridor))
    }
}
