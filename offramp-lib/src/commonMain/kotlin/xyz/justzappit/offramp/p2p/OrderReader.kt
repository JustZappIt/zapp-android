// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import xyz.justzappit.evm.abi.AbiDecoder
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.toHex

enum class OrderStatus(
    val onChain: Int
) {
    PLACED(0),
    ACCEPTED(1),
    PAID(2),
    COMPLETED(3),
    CANCELLED(4);

    companion object {
        fun fromOnChain(value: Int): OrderStatus =
            entries.firstOrNull { it.onChain == value }
                ?: error("Unknown on-chain OrderStatus: $value")
    }
}

data class OrderRead(
    val status: OrderStatus,
    val acceptedMerchant: Address?,
    val merchantPubKey: String,
)

object OrderReader {
    fun decodeOrderSnapshot(returnData: ByteArray, orderId: BigInteger): OrderSnapshot {
        val tuple = tupleDecoder(returnData, ORDER_TUPLE_MIN_HEAD_FULL)

        val orderTypeByte = tuple.uint8(FIELD_ORDER_TYPE)
        val orderType =
            OrderType.entries.firstOrNull { it.onChain == orderTypeByte }
                ?: error("Unknown OrderType from chain: $orderTypeByte")

        return OrderSnapshot(
            orderId = orderId,
            status = OrderStatus.fromOnChain(tuple.uint8(FIELD_STATUS)),
            orderType = orderType,
            circleId = tuple.uint(FIELD_CIRCLE_ID),
            userAddress = tuple.address(FIELD_USER),
            usdcAmount = Usdc6(tuple.uint(FIELD_AMOUNT)),
            fiatAmount = Usdc6(tuple.uint(FIELD_FIAT_AMOUNT)),
            currencyHex = "0x" + tuple.word(FIELD_CURRENCY).toHex(),
            acceptedMerchantAddress = tuple.addressOrNull(FIELD_ACCEPTED_MERCHANT),
            merchantPubKey = tuple.dynamicStringAt(tuple.uint(FIELD_PUBKEY).toInt()),
            encryptedUserUpi = tuple.dynamicStringAt(tuple.uint(FIELD_ENC_UPI).toInt()),
            encryptedMerchantUpi = tuple.dynamicStringAt(tuple.uint(FIELD_ENC_MERCHANT_UPI).toInt()),
            placedAtEpochSeconds = tuple.uint(FIELD_PLACED_TS).toLong().takeIf { it > 0 },
            // On-chain Order tuple only carries placed + completed timestamps.
            acceptedAtEpochSeconds = null,
            paidAtEpochSeconds = null,
            completedAtEpochSeconds = tuple.uint(FIELD_COMPLETED_TS).toLong().takeIf { it > 0 },
            cancelledAtEpochSeconds = null,
            // actualUsdcAmount / actualFiatAmount need a separate getAdditionalOrderDetails call.
            actualUsdcAmount = null,
            actualFiatAmount = null,
            placedTxHash = null,
            source = OrderSnapshot.Source.OnChain,
        )
    }

    fun decodeOrder(returnData: ByteArray): OrderRead {
        val tuple = tupleDecoder(returnData, ORDER_TUPLE_MIN_HEAD)
        return OrderRead(
            status = OrderStatus.fromOnChain(tuple.uint8(FIELD_STATUS)),
            acceptedMerchant = tuple.addressOrNull(FIELD_ACCEPTED_MERCHANT),
            merchantPubKey = tuple.dynamicStringAt(tuple.uint(FIELD_PUBKEY).toInt()),
        )
    }

    fun decodeAddressArrayNonEmpty(returnData: ByteArray): Boolean {
        if (returnData.size < 2 * WORD) return false
        val offset = AbiDecoder(returnData).uint(0).toInt()
        if (offset < 0 || offset + WORD > returnData.size) return false
        val length = BigInteger(1, returnData.copyOfRange(offset, offset + WORD)).toInt()
        return length > 0
    }

    /** Strips the top-level offset slot and returns a decoder positioned at the Order tuple. */
    private fun tupleDecoder(returnData: ByteArray, minTupleHead: Int): AbiDecoder {
        require(returnData.size >= TOP_LEVEL_OFFSET_SLOT * WORD + minTupleHead) {
            "Order return data too short: ${returnData.size} bytes"
        }
        return AbiDecoder(returnData.copyOfRange(TOP_LEVEL_OFFSET_SLOT * WORD, returnData.size))
    }

    private const val WORD = AbiDecoder.WORD
    private const val TOP_LEVEL_OFFSET_SLOT = 1

    // Field positions within the Order struct, ordered as in order-processor-facet.ts.
    private const val FIELD_AMOUNT = 0
    private const val FIELD_FIAT_AMOUNT = 1
    private const val FIELD_PLACED_TS = 2
    private const val FIELD_COMPLETED_TS = 3
    private const val FIELD_ACCEPTED_MERCHANT = 5
    private const val FIELD_USER = 6
    private const val FIELD_PUBKEY = 8
    private const val FIELD_ENC_UPI = 9
    private const val FIELD_STATUS = 11
    private const val FIELD_ORDER_TYPE = 12

    // Positions 13..16 hold the inline disputeInfo (4 static slots); the rest of the
    // Order tuple resumes at 17. Slot indices below already account for that.
    private const val FIELD_ENC_MERCHANT_UPI = 19
    private const val FIELD_CURRENCY = 22
    private const val FIELD_CIRCLE_ID = 24

    private const val ORDER_TUPLE_MIN_HEAD = (FIELD_STATUS + 1) * WORD
    private const val ORDER_TUPLE_MIN_HEAD_FULL = (FIELD_CIRCLE_ID + 1) * WORD
}
