package co.electriccoin.zcash.ui.screen.migration.torfailure

import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.repository.PendingMigrationTorFailureDecisionRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

class MigrationTorFailureVM(
    private val navigationRouter: NavigationRouter,
    private val pendingMigrationTorFailureDecisionRepository: PendingMigrationTorFailureDecisionRepository,
) : ViewModel() {

    val state: StateFlow<MigrationTorFailureState?> =
        flowOf(
            MigrationTorFailureState(
                onContinueWithoutTor = ::onContinueWithoutTor,
                onTryAgain = ::onTryAgain,
                onBack = ::onBack,
            )
        ).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = null,
        )

    private fun onContinueWithoutTor() {
        pendingMigrationTorFailureDecisionRepository.set(useTor = false)
        navigationRouter.back()
    }

    private fun onTryAgain() {
        pendingMigrationTorFailureDecisionRepository.set(useTor = true)
        navigationRouter.back()
    }

    private fun onBack() = navigationRouter.back()
}
