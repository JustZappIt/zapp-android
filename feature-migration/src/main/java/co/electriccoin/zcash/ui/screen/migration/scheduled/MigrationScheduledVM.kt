package co.electriccoin.zcash.ui.screen.migration.scheduled

import androidx.lifecycle.ViewModel
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.migration.formatMigrationDuration
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.provider.IsBackgroundExecutionAvailableProvider
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.GetMigrationSnapshotUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

class MigrationScheduledVM(
    private val getMigrationSnapshot: GetMigrationSnapshotUseCase,
    private val navigationRouter: NavigationRouter,
    private val errorStateMapper: ErrorMapperUseCase,
    private val isBackgroundExecutionAvailableProvider: IsBackgroundExecutionAvailableProvider,
) : ViewModel() {
    private val loadLce = mutableLce<Unit>()

    val state: StateFlow<LceState<MigrationScheduledState>> =
        flow { emit(getMigrationSnapshot()) }
            .map { snapshot ->
                // Transient null (SDK not resolved yet) keeps the LCE loading instead of
                // rendering zeroed stats (review L3).
                if (snapshot == null) return@map null
                val total = snapshot.transfers.sumOf { it.amountZatoshi }
                val count = snapshot.totalCount
                val allScheduled =
                    (snapshot.transfers.map { it.scheduledAt } + snapshot.preparations.map { it.scheduledAt })
                val span =
                    (
                        (allScheduled.maxOrNull() ?: kotlin.time.Instant.DISTANT_PAST) -
                            (allScheduled.minOrNull() ?: kotlin.time.Instant.DISTANT_PAST)
                    ).inWholeSeconds
                val backgroundHint =
                    if (!isBackgroundExecutionAvailableProvider.isAvailable()) {
                        stringRes("Transfers run when you open the app — enable background activity in Settings for automatic sending.")
                    } else {
                        null
                    }
                MigrationScheduledState(
                    totalAmount = stringRes(Zatoshi(total)),
                    transfersProgress = stringRes("0 of $count"),
                    duration = stringRes(formatMigrationDuration(span)),
                    backgroundHint = backgroundHint,
                    onDone = ::onDone,
                )
            }.withLce(loadLce, errorStateMapper::mapToState)
            .stateIn(this)

    private fun onDone() = navigationRouter.backToRoot()
}
