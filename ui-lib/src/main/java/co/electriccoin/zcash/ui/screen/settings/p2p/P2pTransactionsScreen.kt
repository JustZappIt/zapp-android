@file:Suppress("ktlint:standard:filename")

package co.electriccoin.zcash.ui.screen.settings.p2p

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel

@Composable
fun P2pTransactionsScreen() {
    val vm = koinViewModel<P2pTransactionsVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler { state.onBack() }
    P2pTransactionsView(state = state)
}
