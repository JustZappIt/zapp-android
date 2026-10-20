package co.electriccoin.zcash.ui.screen.invest

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.invest.model.InvestEligibility
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettings
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettingsRepository
import co.electriccoin.zcash.ui.screen.invest.gate.InvestGateArgs
import co.electriccoin.zcash.ui.screen.invest.gate.InvestUnavailableArgs
import co.electriccoin.zcash.ui.screen.invest.home.InvestHomeArgs
import co.electriccoin.zcash.ui.screen.invest.intro.InvestIntroArgs

/** Every way into Invest lands on the first screen the user still needs. */
class NavigateToInvestUseCase(
    private val settingsRepository: InvestSettingsRepository,
    private val navigationRouter: NavigationRouter,
) {
    suspend operator fun invoke() {
        navigationRouter.forward(routeFor(settingsRepository.get()))
    }

    companion object {
        fun routeFor(settings: InvestSettings): Any {
            val country = settings.countryCode ?: return InvestGateArgs
            return when {
                // Home is sell-only when the country no longer allows buying.
                settings.setupComplete -> InvestHomeArgs

                settings.eligibility == InvestEligibility.PROHIBITED -> InvestUnavailableArgs(country)

                // A restricted country without the attestation goes back to the gate.
                !settings.isAvailable -> InvestGateArgs

                else -> InvestIntroArgs
            }
        }
    }
}
