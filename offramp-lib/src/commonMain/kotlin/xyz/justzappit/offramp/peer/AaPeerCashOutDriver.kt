// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

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
        flow { emitAll(build().resume(checkpoint)) }

    override fun observeOrder(id: PeerDepositId): Flow<PeerCashOutStatus> =
        flow { emitAll(build().observeOrder(id)) }

    override fun withdraw(id: PeerDepositId, amount: Usdc6): Flow<PeerCashOutStatus> =
        flow { emitAll(build().withdraw(id, amount)) }

    override fun setAcceptingIntents(id: PeerDepositId, accepting: Boolean): Flow<PeerCashOutStatus> =
        flow { emitAll(build().setAcceptingIntents(id, accepting)) }

    override suspend fun activeOrders(): List<PeerOrderSnapshot> = build().activeOrders()

    override suspend fun allOrders(): List<PeerOrderSnapshot> = build().allOrders()

    private suspend fun build(): PeerCashOutOrchestrator {
        val account = submitters.resolve()
        return PeerCashOutOrchestratorImpl(
            network = peerNetwork,
            account = account.address,
            txSubmitter = account.submitter,
            rpcClient = rpc,
            curatorClient = curatorClient,
            indexerClient = indexerClient,
            topUp = topUp,
        )
    }
}
