package co.electriccoin.zcash.ui.screen.invest.gate

import androidx.lifecycle.ViewModel
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.model.InvestEligibility
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import java.util.Locale

internal data class InvestUnavailableState(
    val body: StringResource,
    val onChangeCountry: () -> Unit,
    val onDone: () -> Unit,
)

/**
 * The answer is saved, so Invest stays out of PAY; "Change country" re-opens the gate for a user who picked the
 * wrong one (Settings › Invest, where the plan puts that, is not built yet).
 */
internal class InvestUnavailableVM(
    args: InvestUnavailableArgs,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    val state: InvestUnavailableState =
        InvestUnavailableState(
            body =
                Locale.Builder().setRegion(args.countryCode).build().displayCountry.let { name ->
                    if (InvestEligibility.of(args.countryCode) == InvestEligibility.RESTRICTED) {
                        stringRes(R.string.invest_unavailable_body_restricted, name)
                    } else {
                        stringRes(R.string.invest_unavailable_body_prohibited, name)
                    }
                },
            onChangeCountry = { navigationRouter.replace(InvestGateArgs) },
            onDone = { navigationRouter.back() },
        )
}
