package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.PreferredP2pPaymentMethodProvider
import co.electriccoin.zcash.ui.screen.swap.UpiOfframpArgs

class NavigateToPayMerchantUseCase(
    private val preferredP2pPaymentMethodProvider: PreferredP2pPaymentMethodProvider,
    private val navigationRouter: NavigationRouter,
) {
    suspend operator fun invoke() {
        val paymentMethod = preferredP2pPaymentMethodProvider.get()
        navigationRouter.forward(UpiOfframpArgs(currencyCode = paymentMethod.code))
    }
}
