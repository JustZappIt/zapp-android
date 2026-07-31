package co.electriccoin.zcash.ui.screen.tabs.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.common.datasource.WalletSnapshotDataSource
import co.electriccoin.zcash.ui.common.model.WalletRestoringState
import co.electriccoin.zcash.ui.common.model.WalletSnapshot
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Minimal status payload for the Wallet-tab header sync chip. */
enum class WalletSyncStatus { SYNCED, SYNCING, RESTORING, DISCONNECTED, ERROR, INITIALIZING }

data class WalletSyncChipState(
    val status: WalletSyncStatus,
    val progressPercent: Float,
)

class WalletSyncStateVM(
    walletSnapshotDataSource: WalletSnapshotDataSource,
) : ViewModel() {
    val state: StateFlow<WalletSyncChipState> =
        walletSnapshotDataSource
            .observe()
            .map { snapshot -> toChipState(snapshot) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = WalletSyncChipState(WalletSyncStatus.INITIALIZING, 0f),
            )

    private fun toChipState(snapshot: WalletSnapshot?): WalletSyncChipState {
        if (snapshot == null) return WalletSyncChipState(WalletSyncStatus.INITIALIZING, 0f)
        val percent = (snapshot.progress.decimal * 100f).coerceIn(0f, 100f)
        val status =
            when {
                snapshot.synchronizerError != null -> WalletSyncStatus.ERROR
                snapshot.restoringState == WalletRestoringState.RESTORING -> WalletSyncStatus.RESTORING
                snapshot.status == Synchronizer.Status.SYNCED -> WalletSyncStatus.SYNCED
                snapshot.status == Synchronizer.Status.SYNCING -> WalletSyncStatus.SYNCING
                snapshot.status == Synchronizer.Status.DISCONNECTED -> WalletSyncStatus.DISCONNECTED
                else -> WalletSyncStatus.INITIALIZING
            }
        return WalletSyncChipState(status, percent)
    }
}
