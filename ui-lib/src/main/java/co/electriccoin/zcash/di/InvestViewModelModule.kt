package co.electriccoin.zcash.di

import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.screen.invest.NavigateToInvestUseCase
import co.electriccoin.zcash.ui.screen.invest.buy.InvestBuyArgs
import co.electriccoin.zcash.ui.screen.invest.buy.InvestBuyVM
import co.electriccoin.zcash.ui.screen.invest.common.InvestCurrencyProvider
import co.electriccoin.zcash.ui.screen.invest.common.InvestCurrencyProviderImpl
import co.electriccoin.zcash.ui.screen.invest.common.InvestSession
import co.electriccoin.zcash.ui.screen.invest.demo.InvestDemoControlsVM
import co.electriccoin.zcash.ui.screen.invest.gate.AndroidResidenceHintProvider
import co.electriccoin.zcash.ui.screen.invest.gate.InvestGateVM
import co.electriccoin.zcash.ui.screen.invest.gate.InvestUnavailableVM
import co.electriccoin.zcash.ui.screen.invest.gate.ResidenceHintProvider
import co.electriccoin.zcash.ui.screen.invest.home.InvestHomeVM
import co.electriccoin.zcash.ui.screen.invest.intro.InvestIntroArgs
import co.electriccoin.zcash.ui.screen.invest.intro.InvestIntroVM
import co.electriccoin.zcash.ui.screen.invest.progress.InvestProgressVM
import co.electriccoin.zcash.ui.screen.invest.receipt.InvestReceiptVM
import co.electriccoin.zcash.ui.screen.invest.section.InvestmentsSectionVM
import co.electriccoin.zcash.ui.screen.invest.sell.InvestSellArgs
import co.electriccoin.zcash.ui.screen.invest.sell.InvestSellVM
import co.electriccoin.zcash.ui.screen.invest.sellprogress.InvestSellProgressVM
import co.electriccoin.zcash.ui.screen.invest.settings.InvestSettingsVM
import co.electriccoin.zcash.ui.screen.walletbackup.WalletBackupReturnRoutes
import co.electriccoin.zcash.ui.screen.walletbackup.WalletBackupReturnTarget
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * The Invest screens. Their data layer is investModule; the screens depend on
 * [co.electriccoin.zcash.ui.common.invest.repository.InvestRepository] being bound there.
 */
val investViewModelModule =
    module {
        factoryOf(::NavigateToInvestUseCase)
        factoryOf(::InvestCurrencyProviderImpl) bind InvestCurrencyProvider::class
        single { InvestSession() }
        // A backup started from Invest setup comes back to the intro, so setup carries on.
        factory<WalletBackupReturnRoutes> {
            WalletBackupReturnRoutes { target ->
                when (target) {
                    WalletBackupReturnTarget.INVEST_SETUP -> InvestIntroArgs::class
                    WalletBackupReturnTarget.TABS -> null
                }
            }
        }
        factory<ResidenceHintProvider> { AndroidResidenceHintProvider(androidContext()) }
        viewModel {
            InvestmentsSectionVM(
                investRepository = get(),
                settingsRepository = get(),
                accountDataSource = get(),
                currencyProvider = get(),
                tradeFollower = get(),
                navigateToInvest = get(),
                navigationRouter = get(),
                isInvestEnabled = BuildConfig.IS_INVEST_ENABLED,
            )
        }
        // The same gate serves first setup and Settings › Invest's country change.
        viewModel { (isChange: Boolean) ->
            InvestGateVM(
                settingsRepository = get(),
                hintProvider = get(),
                navigationRouter = get(),
                isChange = isChange,
            )
        }
        viewModelOf(::InvestUnavailableVM)
        viewModelOf(::InvestIntroVM)
        viewModel {
            InvestHomeVM(
                investRepository = get(),
                settingsRepository = get(),
                isTorEnabled = get(),
                currencyProvider = get(),
                tradeFollower = get(),
                session = get(),
                navigationRouter = get(),
                clock = Clock.System,
            )
        }
        viewModel { (args: InvestBuyArgs) ->
            InvestBuyVM(
                args = args,
                investRepository = get(),
                settingsRepository = get(),
                accountDataSource = get(),
                swapRepository = get(),
                currencyProvider = get(),
                tradeFollower = get(),
                navigationRouter = get(),
                clock = Clock.System,
            )
        }
        viewModelOf(::InvestProgressVM)
        viewModelOf(::InvestReceiptVM)
        viewModel { (args: InvestSellArgs) ->
            InvestSellVM(
                args = args,
                investRepository = get(),
                sellRepository = get(),
                swapRepository = get(),
                currencyProvider = get(),
                tradeFollower = get(),
                navigationRouter = get(),
                clock = Clock.System,
            )
        }
        viewModelOf(::InvestSellProgressVM)
        viewModel {
            InvestSettingsVM(settingsRepository = get(), navigationRouter = get(), isDemo = BuildConfig.IS_INVEST_DEMO)
        }
        if (BuildConfig.IS_INVEST_DEMO) viewModelOf(::InvestDemoControlsVM)
    }
