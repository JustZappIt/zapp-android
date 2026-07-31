package co.electriccoin.zcash.ui.screen.topup

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopUpScreen() {
    val vm = koinViewModel<TopUpVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    TopUpView(state)
}

@Serializable
data object TopUpArgs
