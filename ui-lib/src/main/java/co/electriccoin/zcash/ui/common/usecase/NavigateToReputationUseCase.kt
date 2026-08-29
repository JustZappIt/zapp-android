package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.reputation.ReputationArgs

/**
 * Opens the reputation screen for the corridor the user would actually buy in. Limits are
 * per-currency and differ several-fold between them, so the corridor is resolved once, here,
 * rather than defaulted inside the screen.
 */
class NavigateToReputationUseCase(
    private val resolveBuyCorridor: ResolveBuyCorridorUseCase,
    private val navigationRouter: NavigationRouter,
) {
    suspend operator fun invoke() {
        navigationRouter.forward(ReputationArgs(currencyCode = resolveBuyCorridor().code))
    }
}
