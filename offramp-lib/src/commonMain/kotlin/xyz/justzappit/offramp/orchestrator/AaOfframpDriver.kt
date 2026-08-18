// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.orchestrator

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.offramp.account.Erc4337SubmitterProvider
import xyz.justzappit.offramp.config.P2pNetworkConfig
import xyz.justzappit.offramp.funding.OfframpFunding
import xyz.justzappit.offramp.funding.OfframpRefund
import xyz.justzappit.offramp.funding.OfframpTopUp
import xyz.justzappit.offramp.funding.RefundResume
import xyz.justzappit.offramp.p2p.CircleRouter
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.InMemoryOrderRecipientUpiCache
import xyz.justzappit.offramp.p2p.InMemoryRelayIdentityStore
import xyz.justzappit.offramp.p2p.OrderReadSource
import xyz.justzappit.offramp.p2p.OrderRecipientUpiCache
import xyz.justzappit.offramp.p2p.RelayIdentityStore
import xyz.justzappit.offramp.p2p.SubgraphClient
import xyz.justzappit.offramp.p2p.Usdc6

/**
 * Production [OfframpDriver] for the ERC-4337 path. The smart-account address needs an async
 * `factory.getAddress` call, which Koin's synchronous factories can't await — so resolution and
 * submitter construction happen lazily inside the flow (a suspend context), then delegate to a
 * plain [OfframpOrchestrator]. Tests drive the orchestrator directly with an EOA [TxSubmitter].
 */
class AaOfframpDriver(
    private val rpc: BaseRpcClient,
    private val network: P2pNetworkConfig,
    private val submitters: Erc4337SubmitterProvider,
    private val subgraph: SubgraphClient,
    private val orderReader: OrderReadSource,
    private val funding: OfframpFunding,
    private val refund: OfframpRefund,
    private val topUp: OfframpTopUp,
    private val router: CircleRouter = CircleRouter(),
    private val relayIdentityStore: RelayIdentityStore = InMemoryRelayIdentityStore(),
    private val orderRecipientUpiCache: OrderRecipientUpiCache = InMemoryOrderRecipientUpiCache(),
) : OfframpDriver {
    override fun run(
        request: OfframpRequest,
        paymentDetailsProvider: OfframpPaymentDetailsProvider?,
    ): Flow<OfframpStatus> =
        flow {
            emitAll(buildOrchestrator().run(request, paymentDetailsProvider))
        }

    override fun resume(
        checkpoint: OfframpCheckpoint,
        paymentDetailsProvider: OfframpPaymentDetailsProvider?,
    ): Flow<OfframpStatus> =
        flow {
            emitAll(buildOrchestrator().resume(checkpoint, paymentDetailsProvider))
        }

    override fun bridgeToBase(addUsdc: Usdc6, resumeBridgeHandle: String?): Flow<BridgeToBaseStatus> =
        flow {
            emitAll(buildOrchestrator().bridgeToBase(addUsdc, resumeBridgeHandle))
        }

    override suspend fun isMerchantAvailable(usdc: Usdc6, currency: CurrencyCode): Boolean =
        buildOrchestrator().isMerchantAvailable(usdc, currency)

    override fun bridgeFundsBackToZec(orderId: BigInteger?, resume: RefundResume?): Flow<OfframpStatus> =
        flow {
            emitAll(buildOrchestrator().bridgeFundsBackToZec(orderId, resume))
        }

    private suspend fun buildOrchestrator(): OfframpOrchestrator {
        val account = submitters.resolve()
        return OfframpOrchestrator(
            rpc = rpc,
            submitter = account.submitter,
            accountAddress = account.address,
            network = network,
            subgraph = subgraph,
            orderReader = orderReader,
            funding = funding,
            refund = refund,
            topUp = topUp,
            router = router,
            relayIdentityStore = relayIdentityStore,
            orderRecipientUpiCache = orderRecipientUpiCache,
        )
    }
}
