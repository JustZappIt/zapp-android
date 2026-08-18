// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import xyz.justzappit.evm.abi.AbiAddress
import xyz.justzappit.evm.abi.AbiEncoder
import xyz.justzappit.evm.abi.AbiUint
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerZero
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.types.Address

object Erc20Calls {
    fun approveCalldata(spender: Address, amount: Usdc6): ByteArray =
        AbiEncoder.encodeFunctionCall(
            "approve(address,uint256)",
            listOf(AbiAddress(spender), AbiUint(amount.micros)),
        )

    fun balanceOfCalldata(owner: Address): ByteArray =
        AbiEncoder.encodeFunctionCall("balanceOf(address)", listOf(AbiAddress(owner)))

    fun transferCalldata(to: Address, amount: Usdc6): ByteArray =
        AbiEncoder.encodeFunctionCall(
            "transfer(address,uint256)",
            listOf(AbiAddress(to), AbiUint(amount.micros)),
        )
}

/** Reads `usdcAddress.balanceOf(owner)` and returns the 6-decimal micros as a typed [Usdc6]. */
suspend fun BaseRpcClient.getUsdcBalance(usdcAddress: Address, owner: Address): Usdc6 {
    val raw = ethCall(to = usdcAddress, data = Erc20Calls.balanceOfCalldata(owner))
    return Usdc6(if (raw.isEmpty()) bigIntegerZero else BigInteger(1, raw))
}
