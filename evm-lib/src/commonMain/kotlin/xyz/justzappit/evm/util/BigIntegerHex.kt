// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.util

import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerZero

/**
 * Parses a hex string (with or without `0x` prefix) as an unsigned [BigInteger]. Empty or `"0x"`
 * → [BigInteger.ZERO]. Used everywhere RPC responses return hex-encoded numeric fields
 * (eth_chainId, eth_gasPrice, block.baseFeePerGas, UserOp gas limits, …).
 */
fun hexToBigInteger(hex: String): BigInteger {
    val s = hex.removePrefix("0x")
    return if (s.isEmpty()) bigIntegerZero else BigInteger(s, 16)
}
