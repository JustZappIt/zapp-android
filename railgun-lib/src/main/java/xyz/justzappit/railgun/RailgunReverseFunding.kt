// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.railgun

import kotlinx.serialization.Serializable
import java.math.BigInteger

@Serializable
data class RailgunReverseCostRequest(
    val amount: String,
    val railgun: String
)

@Serializable
data class RailgunContractCall(
    val to: String,
    val data: String
)

@Serializable
data class RailgunReverseRequest(
    val amount: String,
    val railgun: String,
    val token: String,
    val calls: List<RailgunContractCall>,
)

@Serializable
data class RailgunReverseCost(
    val debit: String,
    val railgunFee: String,
    val broadcasterFee: String? = null,
    val unshieldFeeBasisPoints: Int,
) {
    init {
        require(unshieldFeeBasisPoints in 0 until FEE_DENOMINATOR)
        listOfNotNull(debit, railgunFee, broadcasterFee).forEach {
            require(it.isNotEmpty() && it.all { digit -> digit in '0'..'9' })
            require(BigInteger(it).bitLength() <= TOKEN_AMOUNT_BITS)
        }
    }

    companion object {
        private const val TOKEN_AMOUNT_BITS = 120
        const val FEE_DENOMINATOR = 10_000
    }
}

@Serializable
data class RailgunReverseTransaction(
    val raw: String,
    val txId: String,
    val cost: RailgunReverseCost
) {
    override fun toString() = "RailgunReverseTransaction(txId=$txId)"
}
