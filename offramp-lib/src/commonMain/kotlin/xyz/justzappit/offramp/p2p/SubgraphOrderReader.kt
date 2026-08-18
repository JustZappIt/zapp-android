// SPDX-License-Identifier: AGPL-3.0-only
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
