package co.electriccoin.zcash.ui.screen.migration.lockexplainer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.HasLockedOrchardDustStorageProvider
import co.electriccoin.zcash.ui.common.usecase.LockOrchardBalanceUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MigrationLockExplainerVM(
    private val navigationRouter: NavigationRouter,
    private val lockOrchardBalance: LockOrchardBalanceUseCase,
    private val hasLockedOrchardDustStorageProvider: HasLockedOrchardDustStorageProvider,
) : ViewModel() {

    val state: StateFlow<MigrationLockExplainerState?> =
        flowOf(
            MigrationLockExplainerState(
                onGotIt = ::onGotIt,
                onBack = ::onBack,
            )
        ).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = null,
        )

    private fun onGotIt() = viewModelScope.launch {
        lockOrchardBalance()
        hasLockedOrchardDustStorageProvider.store(true)
        navigationRouter.back()
    }

    private fun onBack() = navigationRouter.back()
}
