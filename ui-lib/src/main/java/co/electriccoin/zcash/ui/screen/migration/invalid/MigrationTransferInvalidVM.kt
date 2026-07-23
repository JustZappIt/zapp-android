package co.electriccoin.zcash.ui.screen.migration.invalid

import androidx.lifecycle.ViewModel
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.groupLce
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.migration.review.MigrationReviewArgs
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

class MigrationTransferInvalidVM(
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val migrationPlanRepository: MigrationPlanRepository,
    private val navigationRouter: NavigationRouter,
    private val errorStateMapper: ErrorMapperUseCase,
) : ViewModel() {

    private val loadLce = mutableLce<Unit>()
    private val restartLce = mutableLce<Unit>()

    init {
        loadLce.execute { getOrchardMigrationSdk()?.hasInvalidTransfers() ?: false }
    }

    val state: StateFlow<LceState<MigrationTransferInvalidState>> =
        migrationPlanRepository.observe().map { plan ->
            val completed = plan?.completedCount ?: 0
            val total = plan?.totalCount ?: 0
            val invalid = total - completed
            val firstInvalid = completed + 1
            val rangeText = if (invalid > 1) "$firstInvalid–$total" else "$firstInvalid"
            MigrationTransferInvalidState(
                completedCount = completed,
                totalCount = total,
                remainingCount = invalid,
                invalidRange = stringRes(rangeText),
                onContinue = ::onContinue,
                onBack = ::onBack,
            )
        }.withLce(groupLce(loadLce, restartLce), errorStateMapper::mapToState)
            .stateIn(this)

    private fun onContinue() = restartLce.execute {
        val sdk = getOrchardMigrationSdk() ?: error("MigrationTransferInvalidVM: no wallet available to restart")
        sdk.restartCurrentMigrationStep()
        navigationRouter.replace(MigrationReviewArgs(MigrationMode.AUTOMATIC))
    }

    private fun onBack() = navigationRouter.back()
}
