package co.electriccoin.zcash.ui.screen.migration.howitworks

import androidx.lifecycle.ViewModel
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.screen.migration.notesplit.MigrationNoteSplitArgs
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

class MigrationHowItWorksVM(
    private val navigationRouter: NavigationRouter,
    private val errorStateMapper: ErrorMapperUseCase,
) : ViewModel() {

    private val lce = mutableLce<Unit>()

    val state: StateFlow<LceState<MigrationHowItWorksState>> =
        flowOf(
            MigrationHowItWorksState(
                onContinue = ::onContinue,
                onBack = ::onBack,
            )
        ).withLce(lce, errorStateMapper::mapToState)
            .stateIn(this)

    private fun onContinue() = navigationRouter.forward(MigrationNoteSplitArgs)

    private fun onBack() = navigationRouter.back()
}
