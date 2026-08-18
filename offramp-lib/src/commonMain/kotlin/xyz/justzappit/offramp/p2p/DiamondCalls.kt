// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import xyz.justzappit.evm.abi.AbiAddress
import xyz.justzappit.evm.abi.AbiArg
import xyz.justzappit.evm.abi.AbiEncoder
import xyz.justzappit.evm.abi.AbiInt
import xyz.justzappit.evm.abi.AbiString
import xyz.justzappit.evm.abi.AbiUint
import xyz.justzappit.evm.abi.AbiUint8
import xyz.justzappit.evm.abi.AbiUintArray
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.math.bigIntegerZero
import xyz.justzappit.evm.types.Address

enum class OrderType(
    val onChain: Int
) {
    BUY(0),
    SELL(1),
    PAY(2),
}

data class PlaceOrderArgs(
    val relayPubKeyEthCrypto: String,
    val usdcAmount: Usdc6,
    val recipientAddress: Address,
    val orderType: OrderType,
    val currency: CurrencyCode,
    val circleId: BigInteger,
    val fiatAmountLimit: Usdc6 = Usdc6.ZERO,
    val preferredPaymentChannelConfigId: BigInteger = bigIntegerZero,
)

@Suppress("TooManyFunctions")
object DiamondCalls {
    fun placeOrderCalldata(args: PlaceOrderArgs): ByteArray {
        val isBuy = args.orderType == OrderType.BUY
        val pubKey = if (isBuy) "" else args.relayPubKeyEthCrypto
        val userPubKey = if (isBuy) args.relayPubKeyEthCrypto else ""

        val abiArgs =
            listOf<AbiArg>(
                AbiString(pubKey),
                AbiUint(args.usdcAmount.micros),
                AbiAddress(args.recipientAddress),
                AbiUint8(args.orderType.onChain),
                AbiString(""),
                AbiString(userPubKey),
                AbiEncoder.bytes32String(args.currency.code),
                AbiUint(args.preferredPaymentChannelConfigId),
                AbiUint(args.circleId),
                AbiUint(args.fiatAmountLimit.micros),
            )
        return AbiEncoder.encodeFunctionCall(
            "placeOrder(string,uint256,address,uint8,string,string,bytes32,uint256,uint256,uint256)",
            abiArgs,
        )
    }

    fun setSellOrderUpiCalldata(
        orderId: BigInteger,
        encryptedUpiHex: String,
        updatedAmount: BigInteger = bigIntegerZero,
    ): ByteArray =
        AbiEncoder.encodeFunctionCall(
            "setSellOrderUpi(uint256,string,uint256)",
            listOf(
                AbiUint(orderId),
                AbiString(encryptedUpiHex),
                AbiUint(updatedAmount),
            ),
        )

    fun cancelOrderCalldata(orderId: BigInteger): ByteArray =
        AbiEncoder.encodeFunctionCall("cancelOrder(uint256)", listOf(AbiUint(orderId)))

    fun isOrderExpiredCalldata(orderId: BigInteger): ByteArray =
        AbiEncoder.encodeFunctionCall("isOrderExpired(uint256)", listOf(AbiUint(orderId)))

    fun autoCancelExpiredOrdersCalldata(orderIds: List<BigInteger>): ByteArray =
        AbiEncoder.encodeFunctionCall(
            "autoCancelExpiredOrders(uint256[])",
            listOf(AbiUintArray(orderIds)),
        )

    fun getOrdersByIdCalldata(orderId: BigInteger): ByteArray =
        AbiEncoder.encodeFunctionCall(
            "getOrdersById(uint256)",
            listOf(AbiUint(orderId)),
        )

    fun getAdditionalOrderDetailsCalldata(orderId: BigInteger): ByteArray =
        AbiEncoder.encodeFunctionCall(
            "getAdditionalOrderDetails(uint256)",
            listOf(AbiUint(orderId)),
        )

    fun getPriceConfigCalldata(currency: CurrencyCode): ByteArray =
        AbiEncoder.encodeFunctionCall(
            "getPriceConfig(bytes32)",
            listOf(AbiEncoder.bytes32String(currency.code)),
        )

    fun getSmallOrderFixedFeePayCalldata(currency: CurrencyCode): ByteArray =
        AbiEncoder.encodeFunctionCall(
            "getSmallOrderFixedFeePay(bytes32)",
            listOf(AbiEncoder.bytes32String(currency.code)),
        )

    fun getSmallOrderThresholdCalldata(currency: CurrencyCode): ByteArray =
        AbiEncoder.encodeFunctionCall(
            "getSmallOrderThreshold(bytes32)",
            listOf(AbiEncoder.bytes32String(currency.code)),
        )

    fun getAssignableMerchantsFromCircleCalldata(
        circleId: BigInteger,
        assignUpTo: BigInteger,
        currency: CurrencyCode,
        user: Address,
        usdtAmount: Usdc6,
        fiatAmount: Usdc6,
        orderType: OrderType,
        preferredPCConfigId: BigInteger = bigIntegerZero,
    ): ByteArray =
        AbiEncoder.encodeFunctionCall(
            "getAssignableMerchantsFromCircle(uint256,uint256,bytes32,address,uint256,uint256,int256,uint256)",
            listOf(
                AbiUint(circleId),
                AbiUint(assignUpTo),
                AbiEncoder.bytes32String(currency.code),
                AbiAddress(user),
                AbiUint(usdtAmount.micros),
                AbiUint(fiatAmount.micros),
                AbiInt(bigIntegerValueOf(orderType.onChain.toLong())),
                AbiUint(preferredPCConfigId),
            ),
        )
}
