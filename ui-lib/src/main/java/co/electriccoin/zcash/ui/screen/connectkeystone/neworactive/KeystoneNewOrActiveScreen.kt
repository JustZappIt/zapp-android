package co.electriccoin.zcash.ui.screen.connectkeystone.neworactive

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.screen.common.LceRenderer
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun KeystoneNewOrActiveScreen(args: KeystoneNewOrActiveArgs) {
    val vm = koinViewModel<KeystoneNewOrActiveVM> { parametersOf(args) }
    val state by vm.state.collectAsStateWithLifecycle()
    LceRenderer(state) {
        BackHandler { it.onBack() }
        KeystoneNewOrActiveView(it)
    }
}
