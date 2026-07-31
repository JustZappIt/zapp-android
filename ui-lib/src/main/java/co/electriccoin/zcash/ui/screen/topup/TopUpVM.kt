package co.electriccoin.zcash.ui.screen.topup

import androidx.lifecycle.ViewModel
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.NavigationTargets
import co.electriccoin.zcash.ui.screen.receive.ReceiveAddressType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class TopUpVM(
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    val state: StateFlow<TopUpState?> =
        MutableStateFlow<TopUpState?>(
            TopUpState(
                onBack = { navigationRouter.back() },
                // Exchanges (Binance/Coinbase) can only send to a transparent address
                onFromExchange = { onSourcePicked(ReceiveAddressType.Transparent) },
                // Another Zcash wallet can receive directly to the shielded/unified address
                onFromWallet = { onSourcePicked(ReceiveAddressType.Unified) },
            )
        ).asStateFlow()

    private fun onSourcePicked(addressType: ReceiveAddressType) =
        navigationRouter.replace("${NavigationTargets.QR_CODE}/${addressType.ordinal}")
}
