package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ExactInputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ExactOutputSwapTransactionProposal
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
) {
    suspend operator fun invoke(clearSendForm: Boolean = true) {
        val proposal =
            when (accountDataSource.getSelectedAccount()) {
                is ZashiAccount -> zashiProposalRepository.getTransactionProposal()
                is KeystoneAccount -> keystoneProposalRepository.getTransactionProposal()
            }

        zashiProposalRepository.clear()
        keystoneProposalRepository.clear()
        chatSendContext.clear()

        when (proposal) {
            is ExactInputSwapTransactionProposal -> {
                swapRepository.clearQuote()
                navigationRouter.backTo(SwapArgs::class)
            }

            is ExactOutputSwapTransactionProposal -> {
                swapRepository.clearQuote()
                navigationRouter.backTo(UnifiedSendArgs::class)
            }

            else -> {
                if (clearSendForm) observeClearSend.requestClear()
                navigationRouter.backTo(UnifiedSendArgs::class)
            }
        }
    }
}
