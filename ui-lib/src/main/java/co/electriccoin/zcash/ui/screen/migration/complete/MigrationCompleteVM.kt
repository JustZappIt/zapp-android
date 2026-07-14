package co.electriccoin.zcash.ui.screen.migration.complete

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.migration.formatMigrationDuration
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.provider.HasSeenMigrationCompleteStorageProvider
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.common.repository.MockOrchardBalanceRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MigrationCompleteVM(
    private val migrationPlanRepository: MigrationPlanRepository,
    private val mockBalanceRepository: MockOrchardBalanceRepository,
    private val hasSeenMigrationCompleteStorageProvider: HasSeenMigrationCompleteStorageProvider,
    private val navigationRouter: NavigationRouter,
    private val errorStateMapper: ErrorMapperUseCase,
) : ViewModel() {

    private val loadLce = mutableLce<MigrationCompleteState>()

    init {
        loadLce.execute {
            val plan = migrationPlanRepository.load()
            val totalTransferred = plan?.transfers?.sumOf { it.amountZatoshi } ?: 0L
            val totalCount = plan?.totalCount ?: 0
            val firstAt = plan?.transfers?.minOfOrNull { it.scheduledAtEpochSeconds } ?: 0L
            val lastAt = plan?.transfers?.maxOfOrNull { it.scheduledAtEpochSeconds } ?: 0L
            // Whatever's still in the mock Orchard balance once every transfer has sent is the
            // dust/residual left behind (below the migratable threshold, or an un-migrated
            // opt-in residual — either way, it's what's actually still sitting in Orchard).
            val dustZatoshi = mockBalanceRepository.get()

            MigrationCompleteState(
                totalTransferred = stringRes(Zatoshi(totalTransferred)),
                remainingDust = if (dustZatoshi > 0L) stringRes(Zatoshi(dustZatoshi)) else null,
                transfersProgress = stringRes("$totalCount of $totalCount sent"),
                duration = stringRes(formatMigrationDuration(lastAt - firstAt)),
                onDone = ::onDone,
            )
        }
    }

    val state: StateFlow<LceState<MigrationCompleteState>> =
        loadLce.state.map { it.success }.withLce(loadLce, errorStateMapper::mapToState).stateIn(this)

    private fun onDone() {
        // Marks the *banner's* seen-flag too, not a separate one — a user who's already been
        // shown (and dismissed) this dedicated celebration screen doesn't also need the home
        // banner nagging them afterwards; they're the same acknowledgment.
        viewModelScope.launch { hasSeenMigrationCompleteStorageProvider.store(true) }
        navigationRouter.backToRoot()
    }
}
