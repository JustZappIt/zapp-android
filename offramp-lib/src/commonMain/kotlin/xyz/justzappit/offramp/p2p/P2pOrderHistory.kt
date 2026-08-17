// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.config.P2pNetworkConfig

/**
 * Display-ready row for the P2P transactions screen.
 *
 *  - [recipientUpiPlain]: the destination VPA the user typed at placement. Sourced primarily
 *    from [OrderRecipientUpiCache] (written by the orchestrator on placeOrder success) because
 *    on-chain `encUpi` is encrypted to the **merchant's** public key for PAY/SELL and the user
 *    cannot recover it from the chain. Falls back to decrypting `encUpi` with the relay key
 *    only for BUY (where the merchant seals their own pay-to VPA to the user's relay pubkey).
 *  - [merchantUpiPlain]: decrypted `encMerchantUpi`. Populated only after the merchant calls
 *    completeOrder with a non-empty `encMerchantUpi` (empty in practice for many merchants).
 */
data class P2pOrderHistoryItem(
    val orderId: BigInteger,
    val orderType: OrderType,
    val status: OrderStatus,
    val usdcAmount: Usdc6,
    val fiatAmount: Usdc6,
    val currencyHex: String,
    val placedAtEpochSeconds: Long?,
    val completedAtEpochSeconds: Long?,
    val cancelledAtEpochSeconds: Long?,
    val acceptedMerchantAddress: Address?,
    val recipientUpiPlain: String?,
    val merchantUpiPlain: String?,
    val fixedFeePaid: Usdc6?,
)

/**
 * Paginates the subgraph and decrypts the per-order UPI ciphertexts using the persisted relay
 * identity. Stateless and idempotent — the screen VM owns the fetch lifecycle.
 *
 * Strategy: one subgraph round-trip per page (up to [PAGE_SIZE] rows), N parallel ECIES decryptions,
 * and bounded optional on-chain reads. The on-chain reads mirror the official web UI's
 * `getAdditionalOrderDetails(orderId)` enrichment and recover `encMerchantUpi` if the subgraph row
 * is missing it.
 */
class P2pOrderHistorySource(
    private val subgraph: SubgraphClient,
    private val relayIdentityStore: RelayIdentityStore,
    private val orderRecipientUpiCache: OrderRecipientUpiCache = InMemoryOrderRecipientUpiCache(),
    private val onChainOrderReader: OrderReadSource? = null,
    private val rpc: BaseRpcClient? = null,
    private val network: P2pNetworkConfig? = null,
) {
    suspend fun fetchAll(userAddress: Address, maxOrders: Int = MAX_ORDERS): List<P2pOrderHistoryItem> {
        val relay = relayIdentityStore.get()
        val snapshots = paginateUserOrders(userAddress, maxOrders)
        val onChainSemaphore = Semaphore(ON_CHAIN_CONCURRENCY)
        return coroutineScope {
            snapshots
                .map { snapshot ->
                    async {
                        decryptItem(
                            snapshot = snapshot,
                            relay = relay,
                            cachedRecipientUpi = orderRecipientUpiCache.get(snapshot.orderId.toString()),
                            onChainSemaphore = onChainSemaphore,
                        )
                    }
                }.awaitAll()
        }
    }

    private suspend fun paginateUserOrders(userAddress: Address, maxOrders: Int): List<OrderSnapshot> {
        val out = mutableListOf<OrderSnapshot>()
        var skip = 0
        while (out.size < maxOrders) {
            val pageSize = minOf(PAGE_SIZE, maxOrders - out.size)
            val rows = subgraph.ordersForUser(userAddress.lowercaseHex, first = pageSize, skip = skip)
            if (rows.isEmpty()) break
            out += rows.map { SubgraphOrderParser.parse(it) }
            if (rows.size < pageSize) break
            skip += pageSize
        }
        return out
    }

    private suspend fun decryptItem(
        snapshot: OrderSnapshot,
        relay: RelayIdentity?,
        cachedRecipientUpi: String?,
        onChainSemaphore: Semaphore,
    ): P2pOrderHistoryItem {
        // For PAY/SELL, encUpi is encrypted to the merchant; the user can never decrypt it. The
        // local cache (written at placeOrder) is the only path. For BUY, encUpi is sealed to the
        // user's relay key and CAN be decrypted — keep that branch as a fallback.
        val recipientUpi =
            cachedRecipientUpi
                ?: PaymentAddressDecryptor.decrypt(snapshot.encryptedUserUpi, relay)
        val onChainSnapshot = readOnChainSnapshotIfNeeded(snapshot, onChainSemaphore)
        val encryptedMerchantUpi =
            snapshot.encryptedMerchantUpi.ifBlank {
                onChainSnapshot?.encryptedMerchantUpi.orEmpty()
            }
        val merchantUpi = PaymentAddressDecryptor.decrypt(encryptedMerchantUpi, relay)
        val feeDetails = readFeeDetailsIfNeeded(snapshot, onChainSemaphore)
        val actualUsdcAmount =
            feeDetails
                ?.actualUsdcAmount
                ?.takeIf { it > Usdc6.ZERO }
                ?: snapshot.actualUsdcAmount
                ?: snapshot.usdcAmount
        val actualFiatAmount =
            feeDetails
                ?.actualFiatAmount
                ?.takeIf { it > Usdc6.ZERO }
                ?: snapshot.actualFiatAmount
                ?: snapshot.fiatAmount
        return P2pOrderHistoryItem(
            orderId = snapshot.orderId,
            orderType = snapshot.orderType,
            status = snapshot.status,
            usdcAmount = actualUsdcAmount,
            fiatAmount = actualFiatAmount,
            currencyHex = snapshot.currencyHex,
            placedAtEpochSeconds = snapshot.placedAtEpochSeconds,
            completedAtEpochSeconds = snapshot.completedAtEpochSeconds,
            cancelledAtEpochSeconds = snapshot.cancelledAtEpochSeconds,
            acceptedMerchantAddress = snapshot.acceptedMerchantAddress,
            recipientUpiPlain = recipientUpi,
            merchantUpiPlain = merchantUpi,
            fixedFeePaid = feeDetails?.fixedFeePaid,
        )
    }

    private suspend fun readOnChainSnapshotIfNeeded(
        snapshot: OrderSnapshot,
        onChainSemaphore: Semaphore,
    ): OrderSnapshot? {
        val reader = onChainOrderReader
        if (reader == null ||
            snapshot.encryptedMerchantUpi.isNotBlank() ||
            snapshot.status != OrderStatus.COMPLETED
        ) {
            return null
        }
        return onChainSemaphore.withPermit {
            runCatching { reader.fetchOrder(snapshot.orderId) }.getOrNull()
        }
    }

    private suspend fun readFeeDetailsIfNeeded(
        snapshot: OrderSnapshot,
        onChainSemaphore: Semaphore,
    ): OrderFeeDetails? {
        val client = rpc
        val config = network
        if (client == null || config == null || !snapshot.status.hasFeeDetails()) return null
        return onChainSemaphore.withPermit {
            runCatching {
                client.getAdditionalOrderDetails(config.diamondAddress, snapshot.orderId)
            }.getOrNull()
        }
    }

    private fun OrderStatus.hasFeeDetails(): Boolean =
        when (this) {
            OrderStatus.PLACED -> false
            OrderStatus.ACCEPTED, OrderStatus.PAID, OrderStatus.COMPLETED, OrderStatus.CANCELLED -> true
        }

    companion object {
        private const val PAGE_SIZE = 100
        private const val MAX_ORDERS = 500
        private const val ON_CHAIN_CONCURRENCY = 8
    }
}

/**
 * If [plain] is a `upi://pay?…` URI, returns the `pa=` VPA; otherwise returns the input verbatim
 * (the SELL flow seals a bare VPA into encUpi, not a URI). Thin delegate over [UpiQrParser.extractPa]
 * to keep a single canonical UPI parser.
 */
fun extractUpiVpa(plain: String): String = UpiQrParser.extractPa(plain)
