package co.electriccoin.zcash.ui.screen.migration.transferreview

import androidx.lifecycle.ViewModel
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.migration.sending.MigrationSendingArgs
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

class MigrationTransferReviewVM(
    private val migrationPlanRepository: MigrationPlanRepository,
    private val navigationRouter: NavigationRouter,
    private val errorStateMapper: ErrorMapperUseCase,
) : ViewModel() {

    private val loadLce = mutableLce<Unit>()

    val state: StateFlow<LceState<MigrationTransferReviewState>> =
        migrationPlanRepository.observe().map { plan ->
            val next = plan?.nextPending
            if (next == null) {
                // Nothing due right now (e.g. already confirmed from another entry point) —
                // bounce back rather than show a stale/empty review.
                navigationRouter.back()
                return@map null
            }
            MigrationTransferReviewState(
                title = stringRes("Review Transfer ${next.index + 1} of ${plan.totalCount}"),
                body = stringRes(
                    "This transfer sends part of your Orchard balance to Ironwood as part of " +
                        "your scheduled migration.\n\nReview and confirm to send the " +
                        "transaction. Once confirmed, this cannot be undone."
                ),
                amount = stringRes(Zatoshi(next.amountZatoshi)),
                fee = stringRes(Zatoshi(TRANSFER_FEE_ESTIMATE_ZATOSHI)),
                onConfirm = { navigationRouter.forward(MigrationSendingArgs) },
                onBack = ::onBack,
            )
        }.withLce(loadLce, errorStateMapper::mapToState)
            .stateIn(this)

    private fun onBack() = navigationRouter.back()

    companion object {
        // Mock-only placeholder network fee, mirroring the precedent already established by
        // MigrationReviewVM's IMMEDIATE_MODE_MOCK_FEE_ZATOSHI for the same "no fee field on
        // TransferProposal" gap.
        private const val TRANSFER_FEE_ESTIMATE_ZATOSHI = 1_000L
    }
}
