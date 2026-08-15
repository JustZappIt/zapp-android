package co.electriccoin.zcash.di

import co.electriccoin.zcash.ui.screen.swap.peer.PeerCashOutVM
import co.electriccoin.zcash.ui.screen.swap.peer.order.PeerOrderVM
import co.electriccoin.zcash.ui.screen.swap.peer.progress.PeerCashOutProgressVM
import co.electriccoin.zcash.ui.screen.swap.upi.UpiOfframpVM
import co.electriccoin.zcash.ui.screen.swap.upi.bridge.BridgeToBaseVM
import co.electriccoin.zcash.ui.screen.swap.upi.progress.UpiOfframpProgressVM
import co.electriccoin.zcash.ui.screen.swap.upi.scan.ScanUpiVM
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import xyz.justzappit.offramp.peer.PeerDepositId

val offrampViewModelModule =
    module {
        viewModelOf(::UpiOfframpVM)
        viewModelOf(::BridgeToBaseVM)
        viewModelOf(::UpiOfframpProgressVM)
        viewModelOf(::ScanUpiVM)
        viewModelOf(::PeerCashOutVM)
        viewModelOf(::PeerCashOutProgressVM)
        // PeerOrderVM is constructed manually because its clock is a default rather than a binding.
        viewModel { (depositId: PeerDepositId) ->
            PeerOrderVM(
                navigationRouter = get(),
                observeOrder = get(),
                peerCashOutRepository = get(),
                payeeHandleProvider = get(),
                network = get(),
                depositId = depositId,
            )
        }
    }
