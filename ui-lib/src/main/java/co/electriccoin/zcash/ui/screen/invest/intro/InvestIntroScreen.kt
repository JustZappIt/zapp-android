package co.electriccoin.zcash.ui.screen.invest.intro

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Composable
fun InvestIntroScreen() {
    val vm = koinViewModel<InvestIntroVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    state?.let { current ->
        InvestIntroView(state = current)
        BackHandler { current.onBack() }
    }
}

@Serializable
data object InvestIntroArgs
