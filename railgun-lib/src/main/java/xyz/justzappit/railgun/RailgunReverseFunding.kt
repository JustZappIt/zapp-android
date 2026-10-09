// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.railgun

import kotlinx.serialization.Serializable
import xyz.justzappit.evm.types.Address
import java.math.BigInteger

@Serializable
data class RailgunReverseCostRequest(
    @Serializable(with = DecimalSerializer::class)
    val amount: BigInteger,
    val railgun: Address,
    /** Paid to the relayer from what is unshielded, on top of [amount]. */
    @Serializable(with = DecimalSerializer::class)
    val fee: BigInteger,
)

@Serializable
data class RailgunContractCall(
    val to: Address,
    val data: String,
)

@Serializable
data class RailgunReverseRequest(
    @Serializable(with = DecimalSerializer::class)
    val amount: BigInteger,
    val railgun: Address,
    val token: Address,
    val calls: List<RailgunContractCall>,
    val relayAdapt: Address,
    @Serializable(with = DecimalSerializer::class)
    val fee: BigInteger,
)

@Serializable
data class RailgunReverseCost(
    @Serializable(with = DecimalSerializer::class)
    val debit: BigInteger,
    @Serializable(with = DecimalSerializer::class)
    val railgunFee: BigInteger,
)

@Serializable
data class RailgunReverseTransaction(
    val to: Address,
    val data: String,
    @Serializable(with = DecimalSerializer::class)
    val value: BigInteger,
    val cost: RailgunReverseCost,
) {
    override fun toString() = "RailgunReverseTransaction(to=$to)"
}
