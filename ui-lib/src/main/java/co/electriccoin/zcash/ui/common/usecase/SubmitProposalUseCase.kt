package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.ext.convertZatoshiToZec
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.SendTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.provider.ChatSendContextProvider
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.model.MimeTypes
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionArgs
import co.electriccoin.zcash.ui.screen.transactionprogress.TransactionProgressArgs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import xyz.justzappit.zappmessaging.ZappMessagingSDK

class SubmitProposalUseCase(
    private val navigationRouter: NavigationRouter,
    private val accountDataSource: AccountDataSource,
    private val zashiProposalRepository: ZashiProposalRepository,
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val biometricRepository: BiometricRepository,
    private val swapRepository: SwapRepository,
    private val metadataRepository: MetadataRepository,
    private val processSwapTransaction: ProcessSwapTransactionUseCase,
    private val prefillSend: PrefillSendUseCase,
    private val chatSendContext: ChatSendContextProvider,
    private val messagingSDK: ZappMessagingSDK,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Submit Zashi proposal and (by default) navigate to Transaction Progress / Keystone PCZT flow.
     *
     * @param navigateAfter When true (default), navigates to [TransactionProgressArgs] (Zashi) or
     *   [SignKeystoneTransactionArgs] (Keystone) after submit — the standard send/swap UX. When
     *   false, both navigations are suppressed and the caller keeps the foreground. Used by the
     *   UPI offramp's NEAR-bridge funding step so the user stays on the offramp progress screen
     *   while the ZEC deposit submits in the background. The Zashi submit still runs on a
     *   background coroutine either way.
     */
    suspend operator fun invoke(navigateAfter: Boolean = true) {
        try {
            biometricRepository.requestBiometrics(
                request =
                    BiometricRequest(
                        message =
                            stringRes(
                                R.string.authentication_system_ui_subtitle,
                                stringRes(R.string.authentication_use_case_send_funds)
                            )
                    )
            )
            val account = accountDataSource.getSelectedAccount()
            val proposal =
                when (account) {
                    is KeystoneAccount -> keystoneProposalRepository.getTransactionProposal()
                    is ZashiAccount -> zashiProposalRepository.getTransactionProposal()
                }
            if (proposal is SwapTransactionProposal) {
                val selectedSwapAsset = proposal.quote.destinationAsset
                metadataRepository.addSwapAssetToHistory(
                    tokenTicker = selectedSwapAsset.tokenTicker,
                    chainTicker = selectedSwapAsset.chainTicker
                )
            }
            when (account) {
                is KeystoneAccount -> {
                    // Drop any pending chat context: the Keystone flow never posts a chat
                    // receipt, and leaving the latch set would attach the *next* unrelated
                    // Zashi send's receipt to this conversation/request.
                    chatSendContext.consume()
                    if (navigateAfter) {
                        navigationRouter.replace(SignKeystoneTransactionArgs)
                    }
                }

                is ZashiAccount -> {
                    swapRepository.clear()
                    submitZashiProposal(proposal)
                    if (navigateAfter) {
                        navigationRouter.replace(TransactionProgressArgs)
                    }
                }
            }
        } catch (_: BiometricsFailureException) {
            // Auth aborted: drop the pending chat latch so it can't attach to the next unrelated send.
            chatSendContext.consume()
        } catch (_: BiometricsCancelledException) {
            chatSendContext.consume()
        }
    }

    private fun submitZashiProposal(proposal: TransactionProposal) {
        val pendingChatContext = chatSendContext.consume()
        scope.launch {
            try {
                val result = zashiProposalRepository.submit()
                if (proposal is SwapTransactionProposal) {
                    processSwapTransaction(proposal, result)
                }
                // Only a full success may notify the peer: submit() returns failures as
                // values (Failure/GrpcFailure/Error), and a receipt on anything less flips
                // the requester's bubble to "Paid" for money that never landed. Partial is
                // treated as not-landed for the same reason.
                if (pendingChatContext != null &&
                    proposal is SendTransactionProposal &&
                    result is SubmitResult.Success
                ) {
                    notifyChatPeer(pendingChatContext, proposal, result)
                }
            } catch (_: Exception) {
                // do nothing
            } finally {
                prefillSend.clear()
            }
        }
    }

    private suspend fun notifyChatPeer(
        context: ChatSendContextProvider.ChatSendContext,
        proposal: SendTransactionProposal,
        result: SubmitResult.Success,
    ) {
        try {
            val zecAmount =
                proposal.amount
                    .convertZatoshiToZec()
                    .stripTrailingZeros()
                    .toPlainString()
            // Multi-tx proposals (TEX two-step, shield-then-spend) list intermediate txs in
            // submission order; picking one would link the receipt to the wrong transaction.
            // txId is an optional field, so omitting it just leaves the bubble non-clickable.
            val txId = result.txIds.singleOrNull()?.takeIf { it.isNotBlank() }
            val payload =
                JSONObject()
                    .put("amount", zecAmount.toDouble())
                    .put("token", "ZEC")
                    .apply { context.requestId?.let { put("requestId", it) } }
                    .apply { txId?.let { put("txId", it) } }
                    .toString()
            messagingSDK.sendMessage(
                conversationId = context.conversationId,
                content = payload,
                contentType = MimeTypes.ZEC_TRANSACTION,
            )
        } catch (e: Exception) {
            Twig.warn(e) { "Failed to send chat payment notification" }
        }
    }
}
