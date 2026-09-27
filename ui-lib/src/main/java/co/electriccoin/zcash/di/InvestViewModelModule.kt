package co.electriccoin.zcash.di

import co.electriccoin.zcash.ui.screen.invest.NavigateToInvestUseCase
import co.electriccoin.zcash.ui.screen.invest.buy.InvestBuyArgs
import co.electriccoin.zcash.ui.screen.invest.buy.InvestBuyVM
import co.electriccoin.zcash.ui.screen.invest.gate.AndroidResidenceHintProvider
import co.electriccoin.zcash.ui.screen.invest.gate.InvestGateVM
import co.electriccoin.zcash.ui.screen.invest.gate.InvestUnavailableVM
import co.electriccoin.zcash.ui.screen.invest.gate.ResidenceHintProvider
import co.electriccoin.zcash.ui.screen.invest.home.InvestHomeVM
import co.electriccoin.zcash.ui.screen.invest.intro.InvestIntroVM
import co.electriccoin.zcash.ui.screen.invest.progress.InvestBuyDismisser
import co.electriccoin.zcash.ui.screen.invest.progress.InvestProgressVM
import co.electriccoin.zcash.ui.screen.invest.receipt.InvestReceiptVM
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * The Invest screens. Their data layer is investModule; the screens depend on
 * [co.electriccoin.zcash.ui.common.invest.repository.InvestRepository] being bound there.
 */
val investViewModelModule =
    module {
        factoryOf(::NavigateToInvestUseCase)
        factory<ResidenceHintProvider> { AndroidResidenceHintProvider(androidContext()) }
        viewModelOf(::InvestGateVM)
        viewModelOf(::InvestUnavailableVM)
        viewModelOf(::InvestIntroVM)
        viewModel {
            InvestHomeVM(
                investRepository = get(),
                isTorEnabled = get(),
                navigationRouter = get(),
                clock = Clock.System,
            )
        }
        viewModel { (args: InvestBuyArgs) ->
            InvestBuyVM(
                args = args,
                investRepository = get(),
                accountDataSource = get(),
                swapRepository = get(),
                keystoneProposalRepository = get(),
                navigationRouter = get(),
                clock = Clock.System,
            )
        }
        // The buy engine's InvestRepository.dismissBuy replaces this when the branches meet (see InvestBuyDismisser).
        factory<InvestBuyDismisser> {
            InvestBuyDismisser { throw UnsupportedOperationException("dismissBuy arrives with the buy engine") }
        }
        viewModelOf(::InvestProgressVM)
        viewModelOf(::InvestReceiptVM)
    }
