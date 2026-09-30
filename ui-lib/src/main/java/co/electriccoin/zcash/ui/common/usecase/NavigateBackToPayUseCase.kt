package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.tabs.SelectedTabRepository
import co.electriccoin.zcash.ui.screen.tabs.TabsArgs
import co.electriccoin.zcash.ui.screen.tabs.view.ZappTab

/** Back down to the tab shell, on Pay: where the screens a Pay action opened return to. */
class NavigateBackToPayUseCase internal constructor(
    private val navigationRouter: NavigationRouter,
    private val selectedTabRepository: SelectedTabRepository,
) {
    operator fun invoke() {
        selectedTabRepository.select(ZappTab.PAY)
        navigationRouter.backTo(TabsArgs::class)
    }
}
