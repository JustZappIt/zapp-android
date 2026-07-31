package co.electriccoin.zcash.ui.screen.heightinfo

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.common.HeightInfoState
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeightInfoScreen() {
    val navigationRouter = koinInject<NavigationRouter>()
    HeightInfoView(
        state = remember { HeightInfoState(onBack = navigationRouter::back) }
    )
}

@Serializable
data object HeightInfoArgs
