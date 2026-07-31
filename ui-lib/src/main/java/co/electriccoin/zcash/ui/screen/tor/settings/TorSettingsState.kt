package co.electriccoin.zcash.ui.screen.tor.settings

data class TorSettingsState(
    val isOptedIn: Boolean,
    val onSaveClick: (optIn: Boolean) -> Unit,
    val onDismiss: () -> Unit,
)
