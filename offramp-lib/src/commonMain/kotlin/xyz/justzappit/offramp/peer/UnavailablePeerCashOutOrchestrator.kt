// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import xyz.justzappit.offramp.p2p.Usdc6

/**
 * What the rails are on a build Peer does not exist on. Peer is Base mainnet only and the surfaces
 * that lead to it are hidden elsewhere, but the objects behind them hang off the same dependency
 * graph as the tab bar, so a mainnet-only network config resolved there takes the whole screen down.
 *
 * Reporting no orders and refusing each action is the honest answer: the rail is absent, not broken.
 */
object UnavailablePeerCashOutOrchestrator : PeerCashOutOrchestrator {
    override fun createOrder(request: PeerCashOutRequest): Flow<PeerCashOutStatus> =
        unavailable(PeerCashOutStep.INITIALIZATION)

    override fun resume(checkpoint: PeerCashOutCheckpoint): Flow<PeerCashOutStatus> =
        unavailable(PeerCashOutStep.INITIALIZATION, checkpoint.depositId)

    override fun observeOrder(id: PeerDepositId): Flow<PeerCashOutStatus> =
        unavailable(PeerCashOutStep.AWAITING_BUYER, id)

    override fun withdraw(id: PeerDepositId, amount: Usdc6): Flow<PeerCashOutStatus> =
        unavailable(PeerCashOutStep.WITHDRAWING, id)

    override fun setAcceptingIntents(id: PeerDepositId, accepting: Boolean): Flow<PeerCashOutStatus> =
        unavailable(PeerCashOutStep.AWAITING_BUYER, id)

    override suspend fun activeOrders(): List<PeerOrderSnapshot> = emptyList()

    override suspend fun allOrders(): List<PeerOrderSnapshot> = emptyList()

    override suspend fun resolveCheckpoint(checkpoint: PeerCashOutCheckpoint): PeerDepositId =
        throw PeerErrorCode.UNSUPPORTED_PLATFORM.asException()

    private fun unavailable(step: PeerCashOutStep, depositId: PeerDepositId? = null) =
        flowOf(
            PeerCashOutStatus.Failed(
                step = step,
                error = PeerErrorCode.UNSUPPORTED_PLATFORM.asError(),
                depositId = depositId,
            ),
        )
}
