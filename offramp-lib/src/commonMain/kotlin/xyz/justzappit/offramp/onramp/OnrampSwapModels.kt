// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import xyz.justzappit.evm.math.BigDecimal
import xyz.justzappit.evm.math.DecimalRounding
import xyz.justzappit.evm.math.decimalDivide
import xyz.justzappit.evm.math.decimalMultiply
import xyz.justzappit.evm.math.decimalSubtract
import xyz.justzappit.evm.math.decimalToLong
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.p2p.Usdc6

enum class SwapStatus(
    val value: String,
) {
    INCOMPLETE_DEPOSIT("INCOMPLETE_DEPOSIT"),
    PENDING("PENDING"),
    SUCCESS("SUCCESS"),
    REFUNDED("REFUNDED"),
    FAILED("FAILED"),
    PROCESSING("PROCESSING"),
    EXPIRED("EXPIRED"),
    ;

    val isTerminal: Boolean
        get() = this == SUCCESS || this == REFUNDED || this == FAILED || this == EXPIRED
}

data class ValidatedZecSwapQuote(
    val depositAddress: Address,
    val zcashRecipient: String,
    val deadlineMillis: Long,
    val outputZec: String,
    val inputUsd: BigDecimal,
    val outputUsd: BigDecimal,
)

/**
 * What the route costs, as basis points of the input's dollar value. Both sides are priced at quote
 * time, so this is comparable across quotes minutes apart: the market moving carries input and
 * output together, while a worse route or a higher fixed withdrawal fee widens the gap.
 */
val ValidatedZecSwapQuote.costBasisPoints: Int
    get() {
        val difference = decimalSubtract(inputUsd, outputUsd).let { if (it.signum() < 0) ZERO else it }
        return decimalToLong(
            decimalDivide(
                decimalMultiply(difference, MAX_BPS),
                inputUsd,
                0,
                DecimalRounding.UP,
            ),
        ).toInt()
    }

data class OnrampZecSwapResult(
    val status: SwapStatus,
    val outputZec: String,
    val refundedUsdc: Usdc6?,
)

interface OnrampZecSwapGateway {
    suspend fun quote(account: Address, amount: Usdc6): ValidatedZecSwapQuote

    suspend fun notifyDeposit(baseTransactionHash: String, depositAddress: Address)

    suspend fun status(checkpoint: OnrampZecDeliveryCheckpoint): OnrampZecSwapResult
}

const val ZEC_QUOTE_EXPIRY_MARGIN_MILLIS = 60_000L

private val ZERO = BigDecimal("0")
private val MAX_BPS = BigDecimal(OnrampZecDeliveryCheckpoint.MAX_BPS.toString())
