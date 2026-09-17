package co.electriccoin.zcash.ui.screen.swap.picker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun SwapAssetPickerScreen(args: SwapAssetPickerArgs) {
    val vm = koinViewModel<SwapAssetPickerVM> { parametersOf(args) }
    val state by vm.state.collectAsStateWithLifecycle()
    SwapAssetPickerView(state)
}

@Serializable
data class SwapAssetPickerArgs(
    val chainTicker: String?,
    // The repository keeps ZEC out of the swap list; the Send screen asks for it back as the way out of swap mode
    val includeZec: Boolean = false,
)
