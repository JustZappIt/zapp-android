// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.offramp.account.Erc4337SubmitterProvider
import xyz.justzappit.offramp.funding.OfframpTopUp
import xyz.justzappit.offramp.p2p.Usdc6

/**
 * The ERC-4337 [PeerCashOutOrchestrator]. The smart-account address needs an async
 * `factory.getAddress`, which Koin's synchronous factories cannot await, so resolution happens
 * lazily inside the flow and then delegates to a plain orchestrator. Mirrors `AaOfframpDriver`.
 */
class AaPeerCashOutDriver(
    private val rpc: BaseRpcClient,
    private val peerNetwork: PeerNetworkConfig,
    private val submitters: Erc4337SubmitterProvider,
    private val curatorClient: PeerCuratorClient,
    private val indexerClient: PeerIndexerClient,
    private val topUp: OfframpTopUp,
) : PeerCashOutOrchestrator {
    override fun createOrder(request: PeerCashOutRequest): Flow<PeerCashOutStatus> =
        flow { emitAll(build().createOrder(request)) }

    override fun resume(checkpoint: PeerCashOutCheckpoint): Flow<PeerCashOutStatus> =
        flow {
            try {
                emitAll(build(checkpoint).resume(checkpoint))
            } catch (error: CancellationException) {
                throw error
            } catch (error: PeerException) {
                throw error
            } catch (error: Exception) {
                emit(checkpoint.setupFailure(error))
            }
        }

    override fun observeOrder(id: PeerDepositId): Flow<PeerCashOutStatus> =
        flow { emitAll(build().observeOrder(id)) }

    override fun withdraw(id: PeerDepositId, amount: Usdc6): Flow<PeerCashOutStatus> =
        flow { emitAll(build().withdraw(id, amount)) }

    override fun setAcceptingIntents(id: PeerDepositId, accepting: Boolean): Flow<PeerCashOutStatus> =
        flow { emitAll(build().setAcceptingIntents(id, accepting)) }

    override suspend fun activeOrders(): List<PeerOrderSnapshot> = build().activeOrders()

    override suspend fun allOrders(): List<PeerOrderSnapshot> = build().allOrders()

    override suspend fun resolveCheckpoint(checkpoint: PeerCashOutCheckpoint): PeerDepositId =
        build(checkpoint).resolveCheckpoint(checkpoint)

    private suspend fun build(checkpoint: PeerCashOutCheckpoint? = null): PeerCashOutOrchestrator {
        val account = submitters.resolve()
        when (val action = checkpoint?.resumeAction) {
            is PeerResumeAction.ResolveSubmittedDeposit -> {
                account.submitter.restorePendingTransaction(action.txHash, action.submissionNonce)
            }

            is PeerResumeAction.ReconcileSubmission -> {
                account.submitter.restorePendingTransaction(action.submissionHash, action.submissionNonce)
            }

            else -> {
                Unit
            }
        }
        return PeerCashOutOrchestratorImpl(
            network = peerNetwork,
            account = account.address,
            txSubmitter = account.submitter,
            allowanceTransactions = account.allowanceTransactions,
            rpcClient = rpc,
            curatorClient = curatorClient,
            indexerClient = indexerClient,
            topUp = topUp,
        )
    }
}

private fun PeerCashOutCheckpoint.setupFailure(cause: Exception): PeerCashOutStatus.Failed {
    val action = resumeAction
    val hasUnresolvedSubmission =
        action is PeerResumeAction.ResolveSubmittedDeposit || action is PeerResumeAction.ReconcileSubmission
    val pendingHash =
        when (action) {
            is PeerResumeAction.ResolveSubmittedDeposit -> action.txHash
            is PeerResumeAction.ReconcileSubmission -> action.submissionHash
            else -> null
        }
    val code = if (hasUnresolvedSubmission) PeerErrorCode.TRANSACTION_STATUS_UNKNOWN else PeerErrorCode.INITIALIZATION_FAILED
    return PeerCashOutStatus.Failed(
        step = if (hasUnresolvedSubmission) PeerCashOutStep.CREATING_DEPOSIT else PeerCashOutStep.INITIALIZATION,
        error =
            code.asError(
                recovery = pendingHash?.let { PeerRecovery.InspectBaseTransaction(it, "createDeposit") },
                cause = cause,
            ),
        depositId = depositId,
    )
}
