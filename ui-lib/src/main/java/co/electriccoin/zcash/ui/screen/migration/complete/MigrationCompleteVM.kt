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
import co.electriccoin.zcash.ui.common.provider.HasLockedOrchardDustStorageProvider
import co.electriccoin.zcash.ui.common.provider.HasSeenMigrationCompleteStorageProvider
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardBalanceUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.migration.lockexplainer.MigrationLockExplainerArgs
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MigrationCompleteVM(
    private val migrationPlanRepository: MigrationPlanRepository,
    private val getOrchardBalance: GetOrchardBalanceUseCase,
    private val hasSeenMigrationCompleteStorageProvider: HasSeenMigrationCompleteStorageProvider,
    private val hasLockedOrchardDustStorageProvider: HasLockedOrchardDustStorageProvider,
    private val navigationRouter: NavigationRouter,
    private val errorStateMapper: ErrorMapperUseCase,
) : ViewModel() {

    private data class Summary(
        val totalTransferred: Long,
        val totalCount: Int,
        val firstAt: Long,
        val lastAt: Long,
        val dustZatoshi: Long,
    )

    private val loadLce = mutableLce<Summary>()

    init {
        loadLce.execute {
            val plan = migrationPlanRepository.load()
            // Whatever's still in the real Orchard balance once every transfer has sent is the
            // dust/residual left behind (below the migratable threshold, or an un-migrated
            // opt-in residual — either way, it's what's actually still sitting in Orchard).
            Summary(
                totalTransferred = plan?.transfers?.sumOf { it.amountZatoshi } ?: 0L,
                totalCount = plan?.totalCount ?: 0,
                firstAt = plan?.transfers?.minOfOrNull { it.scheduledAtEpochSeconds } ?: 0L,
                lastAt = plan?.transfers?.maxOfOrNull { it.scheduledAtEpochSeconds } ?: 0L,
                dustZatoshi = getOrchardBalance().value,
            )
        }
    }

    val state: StateFlow<LceState<MigrationCompleteState>> =
        combine(loadLce.state, hasLockedOrchardDustStorageProvider.observe()) { lce, isLocked ->
            lce.success?.let { summary -> createState(summary, isLocked) }
        }.withLce(loadLce, errorStateMapper::mapToState).stateIn(this)

    private fun createState(summary: Summary, isLocked: Boolean): MigrationCompleteState =
        MigrationCompleteState(
            totalTransferred = stringRes(Zatoshi(summary.totalTransferred)),
            remainingDust = if (summary.dustZatoshi > 0L) stringRes(Zatoshi(summary.dustZatoshi)) else null,
            isDustLocked = isLocked,
            transfersProgress = stringRes("${summary.totalCount} of ${summary.totalCount} sent"),
            duration = stringRes(formatMigrationDuration(summary.lastAt - summary.firstAt)),
            onDone = ::onDone,
            onMigrateAnyway = ::onDone,
            onLockBalance = ::onLockBalance,
        )

    private fun onDone() {
        // Marks the *banner's* seen-flag too, not a separate one — a user who's already been
        // shown (and dismissed) this dedicated celebration screen doesn't also need the home
        // banner nagging them afterwards; they're the same acknowledgment.
        viewModelScope.launch { hasSeenMigrationCompleteStorageProvider.store(true) }
        navigationRouter.backToRoot()
    }

    private fun onLockBalance() = navigationRouter.forward(MigrationLockExplainerArgs)
}
