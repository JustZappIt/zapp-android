// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import xyz.justzappit.evm.abi.AbiDecoder
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.types.Address

/**
 * Reads `getSmallOrderFixedFeePay(currency)` — the fixed USDC fee the Diamond pulls as a *second*
 * `transferFrom` inside `setSellOrderUpi` for PAY orders at or below [getSmallOrderThreshold].
 */
suspend fun BaseRpcClient.getSmallOrderFixedFeePay(diamondAddress: Address, currency: CurrencyCode): Usdc6 {
    val ret = ethCall(to = diamondAddress, data = DiamondCalls.getSmallOrderFixedFeePayCalldata(currency))
    return Usdc6(AbiDecoder(ret).also { it.requireWords(1) }.uint(0))
}

suspend fun BaseRpcClient.getSmallOrderThreshold(diamondAddress: Address, currency: CurrencyCode): Usdc6 {
    val ret = ethCall(to = diamondAddress, data = DiamondCalls.getSmallOrderThresholdCalldata(currency))
    return Usdc6(AbiDecoder(ret).also { it.requireWords(1) }.uint(0))
}

data class PayFeeConfig(
    val threshold: Usdc6,
    val fixedFee: Usdc6,
) {
    fun feeFor(amount: Usdc6): Usdc6 = if (amount <= threshold) fixedFee else Usdc6.ZERO
}

suspend fun BaseRpcClient.getPayFeeConfig(diamondAddress: Address, currency: CurrencyCode): PayFeeConfig =
    PayFeeConfig(
        threshold = getSmallOrderThreshold(diamondAddress, currency),
        fixedFee = getSmallOrderFixedFeePay(diamondAddress, currency),
    )
