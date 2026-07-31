package co.electriccoin.zcash.di

import co.electriccoin.zcash.ui.screen.swap.upi.UpiOfframpVM
import co.electriccoin.zcash.ui.screen.swap.upi.bridge.BridgeToBaseVM
import co.electriccoin.zcash.ui.screen.swap.upi.progress.UpiOfframpProgressVM
import co.electriccoin.zcash.ui.screen.swap.upi.scan.ScanUpiVM
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val offrampViewModelModule =
    module {
        viewModelOf(::UpiOfframpVM)
        viewModelOf(::BridgeToBaseVM)
        viewModelOf(::UpiOfframpProgressVM)
        viewModelOf(::ScanUpiVM)
    }
