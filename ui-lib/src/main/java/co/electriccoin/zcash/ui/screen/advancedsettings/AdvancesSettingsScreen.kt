@file:Suppress("ktlint:standard:filename")

package co.electriccoin.zcash.ui.screen.advancedsettings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun AdvancedSettingsScreen() {
    val vm = koinViewModel<AdvancedSettingsVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    AdvancedSettings(state = state)
}

@Serializable
data object AdvancedSettingsArgs
