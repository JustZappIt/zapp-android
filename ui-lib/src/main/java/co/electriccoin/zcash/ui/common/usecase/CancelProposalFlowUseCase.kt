package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ExactInputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ExactOutputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.MigrationSweepTransactionProposal
import co.electriccoin.zcash.ui.common.migration.MigrationNavigator
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.provider.ChatSendContextProvider
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.screen.swap.SwapArgs
import co.electriccoin.zcash.ui.screen.unifiedsend.UnifiedSendArgs

class CancelProposalFlowUseCase(
    private val zashiProposalRepository: ZashiProposalRepository,
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val navigationRouter: NavigationRouter,
    private val observeClearSend: ObserveClearSendUseCase,
    private val accountDataSource: AccountDataSource,
    private val swapRepository: SwapRepository,
    private val chatSendContext: ChatSendContextProvider,
    private val migrationNavigator: MigrationNavigator,
) {
    suspend operator fun invoke(clearSendForm: Boolean = true) {
        val proposal =
            when (accountDataSource.getSelectedAccount()) {
                is ZashiAccount -> zashiProposalRepository.getTransactionProposal()
                is KeystoneAccount -> keystoneProposalRepository.getTransactionProposal()
            }

        // A caller that stayed on the stack for the signature owns where a reject lands; the routes
        // below were never pushed on its back stack, so they would silently no-op.
        val signReturnRoute = keystoneProposalRepository.signReturnRoute

        zashiProposalRepository.clear()
        keystoneProposalRepository.clear()
        chatSendContext.clear()

        if (signReturnRoute != null) {
            navigationRouter.backTo(signReturnRoute)
            return
        }

        when (proposal) {
            is ExactInputSwapTransactionProposal -> {
                swapRepository.clearQuote()
                navigationRouter.backTo(SwapArgs::class)
            }

            is ExactOutputSwapTransactionProposal -> {
                swapRepository.clearQuote()
                navigationRouter.backTo(UnifiedSendArgs::class)
            }

            is MigrationSweepTransactionProposal -> {
                // Reached via MigrationReviewVM's IMMEDIATE-mode Keystone branch, never via the
                // ordinary Send flow — Send was never on this back stack, so falling through to
                // the `else` branch's `backTo(Send::class)` would silently no-op (no matching
                // destination to pop to), leaving the user stuck on the Sign/reject sheet.
                migrationNavigator.backToMigrationReview()
            }

            else -> {
                if (clearSendForm) observeClearSend.requestClear()
                navigationRouter.backTo(UnifiedSendArgs::class)
            }
        }
    }
}
