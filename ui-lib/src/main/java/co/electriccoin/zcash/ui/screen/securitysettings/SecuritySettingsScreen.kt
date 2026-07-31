package co.electriccoin.zcash.ui.screen.securitysettings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.screen.securitysettings.view.SecuritySettingsView
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Serializable
data object SecuritySettingsArgs

@Composable
internal fun SecuritySettingsScreen() {
    val vm = koinViewModel<SecuritySettingsVM>()
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val pinError by vm.pinError.collectAsStateWithLifecycle()
    val pinLockoutSeconds by vm.pinLockoutSeconds.collectAsStateWithLifecycle()
    val bioError by vm.bioError.collectAsStateWithLifecycle()
    val isEnrollingBio by vm.isEnrollingBio.collectAsStateWithLifecycle()

    BackHandler { vm.onBack() }

    SecuritySettingsView(
        state = uiState,
        pinError = pinError,
        lockoutSeconds = pinLockoutSeconds,
        bioError = bioError,
        isEnrollingBio = isEnrollingBio,
        onTabSelected = vm::onTabSelected,
        onSaveChanges = vm::onSaveChanges,
        onPinSubmit = vm::submitCurrentPin,
        onNewPinConfirmed = vm::onNewPinConfirmed,
        onBioEnroll = vm::onBioEnroll,
        onClearSuccessMessage = vm::clearSuccessMessage,
        onBack = vm::onBack,
    )
}
