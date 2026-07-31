package co.electriccoin.zcash.ui.screen.topup

import androidx.navigation.NavBackStackEntry
import co.electriccoin.zcash.ui.BaseNavigationCommand
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.NavigationTargets
import co.electriccoin.zcash.ui.screen.receive.ReceiveAddressType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals

class TopUpVMTest {
    private val navigationRouter = RecordingNavigationRouter()
    private val viewModel = TopUpVM(navigationRouter)

    @Test
    fun exchangeUsesTransparentReceiveAddress() {
        requireNotNull(viewModel.state.value).onFromExchange()

        assertEquals(
            listOf("${NavigationTargets.QR_CODE}/${ReceiveAddressType.Transparent.ordinal}"),
            navigationRouter.replacedRoutes,
        )
    }

    @Test
    fun anotherWalletUsesUnifiedReceiveAddress() {
        requireNotNull(viewModel.state.value).onFromWallet()

        assertEquals(
            listOf("${NavigationTargets.QR_CODE}/${ReceiveAddressType.Unified.ordinal}"),
            navigationRouter.replacedRoutes,
        )
    }
}

private class RecordingNavigationRouter : NavigationRouter {
    val replacedRoutes = mutableListOf<String>()

    override fun forward(vararg routes: Any) = Unit

    override fun replace(vararg routes: Any) {
        replacedRoutes.addAll(routes.map { it.toString() })
    }

    override fun replaceAll(vararg routes: Any) = Unit

    override fun back() = Unit

    override fun backTo(route: KClass<*>) = Unit

    override fun custom(block: (NavBackStackEntry?) -> NavigationCommand?) = Unit

    override fun backToRoot() = Unit

    override fun observePipeline(): Flow<BaseNavigationCommand> = emptyFlow()
}
