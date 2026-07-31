package co.electriccoin.zcash.ui.screen.home.backup

data class WalletBackupDetailState(
    val onBack: () -> Unit,
    val onNextClick: () -> Unit,
    val onInfoClick: () -> Unit,
)
