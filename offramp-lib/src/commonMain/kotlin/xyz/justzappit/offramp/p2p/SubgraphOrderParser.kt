// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.math.BigInteger

internal object SubgraphOrderParser {
    fun parse(node: JsonObject): OrderSnapshot {
        val typeValue = node.requireString("type").toInt()
        val statusValue = node.requireString("status").toInt()
        return OrderSnapshot(
            orderId = BigInteger(node.requireString("orderId")),
            status = OrderStatus.fromOnChain(statusValue),
            orderType = orderTypeFromOnChain(typeValue),
            circleId = BigInteger(node.requireString("circleId")),
            userAddress = parseNullableAddress(node.requireString("userAddress")) ?: Address.ZERO,
            usdcAmount = Usdc6(BigInteger(node.requireString("usdcAmount"))),
            fiatAmount = Usdc6(BigInteger(node.requireString("fiatAmount"))),
            currencyHex = node.requireString("currency"),
            acceptedMerchantAddress = parseNullableAddress(node.optionalString("acceptedMerchantAddress")),
            merchantPubKey = node.optionalString("pubkey").orEmpty(),
            encryptedUserUpi = node.optionalString("encUpi").orEmpty(),
            encryptedMerchantUpi = node.optionalString("encMerchantUpi").orEmpty(),
            placedAtEpochSeconds = parseEpochSecondsOrNull(node.optionalString("placedAt")),
            acceptedAtEpochSeconds = parseEpochSecondsOrNull(node.optionalString("acceptedAt")),
            paidAtEpochSeconds = parseEpochSecondsOrNull(node.optionalString("paidAt")),
            completedAtEpochSeconds = parseEpochSecondsOrNull(node.optionalString("completedAt")),
            cancelledAtEpochSeconds = parseEpochSecondsOrNull(node.optionalString("cancelledAt")),
            actualUsdcAmount =
                node
                    .optionalString("actualUsdcAmount")
                    ?.takeIf { it != "0" }
                    ?.let(::BigInteger)
                    ?.let(::Usdc6),
            actualFiatAmount =
                node
                    .optionalString("actualFiatAmount")
                    ?.takeIf { it != "0" }
                    ?.let(::BigInteger)
                    ?.let(::Usdc6),
            placedTxHash = node.optionalString("transactionHash")?.takeIf { it.isNotBlank() }?.let(TxHash::fromHex),
            source = OrderSnapshot.Source.Subgraph,
        )
    }

    private fun orderTypeFromOnChain(onChain: Int): OrderType =
        OrderType.entries.firstOrNull { it.onChain == onChain }
            ?: error("Unknown OrderType from subgraph: $onChain")

    private fun JsonObject.requireString(key: String): String =
        this[key]?.jsonPrimitive?.content
            ?: error("subgraph order response missing required field '$key'")

    private fun JsonObject.optionalString(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    /**
     * Normalises a hex-encoded address (subgraph `Bytes` scalar) into a typed [Address]. Returns
     * `null` if the input is blank/zero/non-hex/over-length. The subgraph's `Bytes` scalar omits
     * leading zeros, so `0x00000000` and `0x000...` (40 zeros) both map to `null`.
     */
    private fun parseNullableAddress(hex: String?): Address? {
        if (hex.isNullOrBlank()) return null
        val cleaned = hex.removePrefix("0x").lowercase()
        if (cleaned.isEmpty()) return null
        if (cleaned.all { it == '0' }) return null
        if (!cleaned.all { it.isAsciiHexDigit() }) return null
        if (cleaned.length > Address.HEX_LEN) return null
        return Address.parseOrNull("0x" + cleaned.padStart(Address.HEX_LEN, '0'))
    }

    private fun parseEpochSecondsOrNull(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val parsed = value.toLongOrNull() ?: return null
        return if (parsed == 0L) null else parsed
    }

    private fun Char.isAsciiHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
