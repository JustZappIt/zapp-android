package co.electriccoin.zcash.ui.screen.migration.notification

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.GetMigrationPrivacyOrReviewDestinationUseCase
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

class MigrationNotificationVM(
    private val args: MigrationNotificationArgs,
    private val navigationRouter: NavigationRouter,
    private val errorStateMapper: ErrorMapperUseCase,
    private val getMigrationPrivacyOrReviewDestination: GetMigrationPrivacyOrReviewDestinationUseCase,
) : ViewModel() {

    private val lce = mutableLce<Unit>()

    val state: StateFlow<LceState<MigrationNotificationState>> =
        flowOf(
            MigrationNotificationState(
                onAllow = ::onAllow,
                onSkip = ::onSkip,
                onAutoSkip = ::onAutoSkip,
                onBack = ::onBack,
            )
        ).withLce(lce, errorStateMapper::mapToState)
            .stateIn(this)

    private fun onAllow() = viewModelScope.launch {
        navigationRouter.forward(destination())
    }

    private fun onSkip() = viewModelScope.launch {
        navigationRouter.forward(destination())
    }

    // Used when this screen skips itself without ever being shown (permission already granted) —
    // replace instead of forward so it doesn't linger in the back stack and bounce the user
    // straight back here when they press back from a later screen.
    private fun onAutoSkip() = viewModelScope.launch {
        navigationRouter.replace(destination())
    }

    private suspend fun destination() =
        getMigrationPrivacyOrReviewDestination(mode = MigrationMode.AUTOMATIC, backgroundAvailable = args.backgroundAvailable)

    private fun onBack() = navigationRouter.back()
}
