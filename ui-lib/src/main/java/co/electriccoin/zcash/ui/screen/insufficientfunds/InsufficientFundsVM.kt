package co.electriccoin.zcash.ui.screen.insufficientfunds

import androidx.lifecycle.ViewModel
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.topup.TopUpArgs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class InsufficientFundsVM(
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    val state: StateFlow<InsufficientFundsState?> =
        MutableStateFlow<InsufficientFundsState?>(
            InsufficientFundsState(
                onBack = { navigationRouter.back() },
                onAddZec = { navigationRouter.replace(TopUpArgs) },
            )
        ).asStateFlow()
}
