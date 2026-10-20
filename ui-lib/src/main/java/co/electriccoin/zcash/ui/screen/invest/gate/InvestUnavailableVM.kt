package co.electriccoin.zcash.ui.screen.invest.gate

import androidx.lifecycle.ViewModel
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.model.InvestEligibility
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.common.InvestFormat

internal class InvestUnavailableVM(
    args: InvestUnavailableArgs,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    val state: InvestUnavailableState =
        InvestUnavailableState(
            body =
                InvestFormat.countryName(args.countryCode).let { name ->
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
