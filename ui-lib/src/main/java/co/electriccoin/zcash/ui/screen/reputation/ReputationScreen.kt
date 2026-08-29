package co.electriccoin.zcash.ui.screen.reputation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ReputationScreen(args: ReputationArgs) {
    val vm = koinViewModel<ReputationVM> { parametersOf(args) }
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(vm) {
        // Re-read on return: a completed verification or buy has already moved these numbers.
        vm.onScreenVisible()
        onPauseOrDispose {}
    }
    ReputationView(state)
    BackHandler { state.onBack() }
}

/**
 * The corridor travels with the route because limits are per-currency and differ several-fold —
 * the same wallet's buy limit is three times larger on BRL than on INR. Showing one corridor's
 * number to a user who buys in another is off by more than rounding.
 */
@Serializable
data class ReputationArgs(
    val currencyCode: String,
)
