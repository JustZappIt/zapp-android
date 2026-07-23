package co.electriccoin.zcash.ui.screen.migration.lockexplainer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.provider.HasLockedOrchardDustStorageProvider
import co.electriccoin.zcash.ui.common.usecase.LockOrchardBalanceUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class MigrationLockExplainerVM(
    private val navigationRouter: NavigationRouter,
    private val lockOrchardBalance: LockOrchardBalanceUseCase,
    private val hasLockedOrchardDustStorageProvider: HasLockedOrchardDustStorageProvider,
) : ViewModel() {

    // Errors flow through the project's standard MutableLce mechanism (its execute() already
    // wraps the block in a try/catch — see MutableLce's kdoc) rather than an ad-hoc try/catch here,
    // which previously left an exception free to crash via viewModelScope.
    private val lockLce = mutableLce<Unit>()

    val state: StateFlow<MigrationLockExplainerState?> =
        lockLce.state.map { lce ->
            MigrationLockExplainerState(
                isLocking = lce.loading,
                onGotIt = ::onGotIt,
                onBack = ::onBack,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = null,
        )

    private fun onGotIt() = lockLce.execute {
        lockOrchardBalance()
        hasLockedOrchardDustStorageProvider.store(true)
        navigationRouter.back()
    }

    private fun onBack() = navigationRouter.back()
}
