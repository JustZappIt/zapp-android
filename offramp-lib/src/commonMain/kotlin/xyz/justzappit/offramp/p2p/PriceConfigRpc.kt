// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.types.Address

/** Calls `diamondAddress.getPriceConfig(currency)` and decodes the four-word return. */
suspend fun BaseRpcClient.getPriceConfig(diamondAddress: Address, currency: CurrencyCode): PriceConfig {
    val raw = ethCall(to = diamondAddress, data = DiamondCalls.getPriceConfigCalldata(currency))
    return PriceConfigDecoder.decode(raw)
}
