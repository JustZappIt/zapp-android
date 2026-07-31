package co.electriccoin.zcash.ui.screen.advancedsettings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UsbOff
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.WalletRestoringState
import co.electriccoin.zcash.ui.common.usecase.GetWalletAccountsUseCase
import co.electriccoin.zcash.ui.common.usecase.GetWalletRestoringStateUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToExportPrivateDataUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToResetWalletUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToTaxExportUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToWalletBackupUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.advancedsettings.debug.DebugArgs
import co.electriccoin.zcash.ui.screen.disconnect.DisconnectArgs
import co.electriccoin.zcash.ui.screen.hotfix.enhancement.EnhancementHotfixArgs
import co.electriccoin.zcash.ui.screen.hotfix.ephemeral.EphemeralHotfixArgs
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AdvancedSettingsVM(
    getWalletRestoringState: GetWalletRestoringStateUseCase,
    getWalletAccounts: GetWalletAccountsUseCase,
    private val navigationRouter: NavigationRouter,
    private val navigateToWalletBackup: NavigateToWalletBackupUseCase,
    private val navigateToResetWallet: NavigateToResetWalletUseCase,
    private val navigateToExportPrivateData: NavigateToExportPrivateDataUseCase,
    private val navigateToTaxExport: NavigateToTaxExportUseCase,
) : ViewModel() {
    val state: StateFlow<AdvancedSettingsState> =
        combine(
            getWalletRestoringState.observe(),
            getWalletAccounts.observe()
        ) { walletState, accounts ->
            createState(walletState, accounts)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue =
                createState(
                    getWalletRestoringState.observe().value,
                    getWalletAccounts.observe().value
                )
        )

    private fun createState(
        walletRestoringState: WalletRestoringState,
        accounts: List<WalletAccount>?
    ): AdvancedSettingsState {
        val hasKeystoneAccount = accounts?.any { it is KeystoneAccount } == true
        val restoring = walletRestoringState == WalletRestoringState.RESTORING

        return AdvancedSettingsState(
            onBack = ::onBack,
            items =
                listOfNotNull(
                    AdvancedSettingsItem(
                        title = stringRes(R.string.advanced_settings_recovery),
                        icon = Icons.Default.Key,
                        onClick = ::onSeedRecoveryClick,
                    ),
                    AdvancedSettingsItem(
                        title = stringRes(R.string.advanced_settings_export),
                        icon = Icons.Default.FileDownload,
                        onClick = ::onExportPrivateDataClick,
                    ),
                    AdvancedSettingsItem(
                        title = stringRes(R.string.advanced_settings_tax),
                        icon = Icons.Default.Receipt,
                        isEnabled = !restoring,
                        onClick = ::onTaxExportClick,
                    ),
                    AdvancedSettingsItem(
                        title = stringRes(R.string.advanced_settings_discover_funds),
                        icon = Icons.Default.Search,
                        onClick = ::onDiscoverFundsClick,
                    ),
                    AdvancedSettingsItem(
                        title = stringRes(R.string.advanced_settings_refresh_transaction_data),
                        icon = Icons.Default.Refresh,
                        onClick = ::onRefreshTransactionDataClick,
                    ),
                    AdvancedSettingsItem(
                        title = stringRes(R.string.advanced_settings_disconnect_hw_wallet),
                        icon = Icons.Default.UsbOff,
                        onClick = ::onDisconnectHwWalletClick,
                    ).takeIf { hasKeystoneAccount },
                    AdvancedSettingsItem(
                        title = stringRes(R.string.advanced_settings_developer_tools),
                        icon = Icons.Default.BugReport,
                        onClick = ::onDebugMenuClick,
                    ).takeIf { BuildConfig.DEBUG },
                ),
            onDeleteWallet = ::onResetWalletClick,
        )
    }

    fun onBack() = navigationRouter.back()

    private fun onSeedRecoveryClick() =
        viewModelScope.launch {
            navigateToWalletBackup(isOpenedFromSeedBackupInfo = false)
        }

    private fun onExportPrivateDataClick() = viewModelScope.launch { navigateToExportPrivateData() }

    private fun onTaxExportClick() = viewModelScope.launch { navigateToTaxExport() }

    private fun onDiscoverFundsClick() = navigationRouter.forward(EphemeralHotfixArgs(address = null))

    private fun onRefreshTransactionDataClick() = navigationRouter.forward(EnhancementHotfixArgs)

    private fun onDisconnectHwWalletClick() = navigationRouter.forward(DisconnectArgs)

    private fun onDebugMenuClick() = navigationRouter.forward(DebugArgs)

    private fun onResetWalletClick() = viewModelScope.launch { navigateToResetWallet() }
}
