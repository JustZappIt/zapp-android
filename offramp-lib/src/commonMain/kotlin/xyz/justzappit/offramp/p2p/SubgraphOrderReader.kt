// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import xyz.justzappit.evm.math.BigInteger

class SubgraphOrderReader(
    private val subgraph: SubgraphClient,
) : OrderReadSource {
    override suspend fun fetchOrder(orderId: BigInteger): OrderSnapshot? {
        val raw = subgraph.rawOrderById(orderId.toString()) ?: return null
        return SubgraphOrderParser.parse(raw)
    }
}
