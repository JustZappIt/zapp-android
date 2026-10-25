// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

@file:UseSerializers(LowercaseAddressSerializer::class)

package xyz.justzappit.offramp.deposits

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.math.bigIntegerZero
import xyz.justzappit.evm.math.div
import xyz.justzappit.evm.math.minus
import xyz.justzappit.evm.math.plus
import xyz.justzappit.evm.math.times
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.offramp.atomicswap.LowercaseAddressSerializer
import xyz.justzappit.offramp.atomicswap.RAILGUN_FEE
import xyz.justzappit.offramp.atomicswap.RelayerTerms
import xyz.justzappit.offramp.atomicswap.SWAP_AMOUNT_BITS
import xyz.justzappit.offramp.peer.Bps

/**
 * What a wallet pins of a deployment's deposits, per network, and never takes from a relayer: the chain, the Deposits
 * [contract] every authorization goes to, its [token], and [relayer], the relayer's deposits account. Each
 * authorization's nonce commits to that account and its fee goes there; it is not the top-level relayer that payouts
 * are signed for.
 */
data class DepositsPin(
    val chainId: ChainId,
    val contract: Address,
    /** What deposits are in: USDC on Ethereum, tUSD on Sepolia. */
    val token: Address,
    val relayer: Address,
)

/** The relayer's `deposits` terms, held to the pin, in the token's base units. */
data class DepositTerms(
    /** The relayer's deposits account, the pinned one. */
    val relayer: Address,
    /** The fee before its percentage; a rescue pays it alone. */
    val fee: BigInteger,
    /** The fee's part of what a deposit moves, in basis points. */
    val feeBps: Int,
    /** Unix seconds until which an authorization signed with [fee] is taken though gas rose; none where gas doesn't. */
    val feeExpiresAt: Long?,
    /** The least a deposit moves. */
    val minValue: BigInteger,
    /** The latest an authorization's `validBefore` may be, in seconds from now. */
    val maxValidity: Long,
) {
    /** How long an authorization is signed for: an hour, or what the relayer takes less a minute if that's less. */
    val validity: Long get() = minOf(VALIDITY_SECONDS, maxValidity - CLOCK_SLACK_SECONDS)
}

/** What a relayer's terms say of deposits for the pinned deployment. */
sealed interface DepositTermsRead {
    data class Pinned(
        val terms: DepositTerms
    ) : DepositTermsRead

    /** It takes no deposits, or none this build can read: they're hidden, and nothing goes to it. */
    data object Unavailable : DepositTermsRead

    /** It takes deposits for another deployment: they're hidden, and nothing is ever posted to it. */
    data object OtherDeployment : DepositTermsRead
}

/**
 * [RelayerTerms.deposits] once they're [pin]'s: its chain, Deposits contract, token and deposits account. Another
 * deployment's are never followed, since the nonce commits to the account and a tampered answer could take even the
 * fee; terms that don't read, or that couldn't be signed under, take no deposits.
 */
fun RelayerTerms.depositTerms(pin: DepositsPin): DepositTermsRead {
    val wire =
        deposits
            ?.takeUnless { it is JsonNull }
            ?.let { runCatching { wireJson.decodeFromJsonElement(WireDepositTerms.serializer(), it) }.getOrNull() }
    return when {
        chainId != pin.chainId -> DepositTermsRead.OtherDeployment
        wire == null -> DepositTermsRead.Unavailable
        !wire.isFor(pin) -> DepositTermsRead.OtherDeployment
        else -> wire.usable()?.let(DepositTermsRead::Pinned) ?: DepositTermsRead.Unavailable
    }
}

/** What deposits cost and bring, as zecSwap's relayer and Railgun reckon them, in the token's base units. */
object DepositFees {
    /** The fixed part of the most this wallet lets a relayer keep of a deposit, 2 USDC: all a rescue may cost. */
    val CAP_FEE: BigInteger = bigIntegerValueOf(CAP_FEE_UNITS)

    /** The cap's part of what a deposit moves, 0.5%, so it grows as the relayer's fee does. */
    const val CAP_FEE_BPS: Int = 50

    /** A deposit of [value]'s fee: [fee] and its [feeBps] part of [value], rounded up (zecswap-api's `deposit_fee`). */
    fun fee(
        fee: BigInteger,
        feeBps: Int,
        value: BigInteger,
    ): BigInteger {
        val share = value * bigIntegerValueOf(feeBps.toLong())
        val max = bigIntegerValueOf(Bps.MAX.toLong())
        return fee + (share + max - bigIntegerValueOf(1)) / max
    }

    /** The most this wallet pays for a deposit of [value]: 2 USDC and 0.5% of it. */
    fun cap(value: BigInteger): BigInteger = fee(CAP_FEE, CAP_FEE_BPS, value)

    /** What a deposit of [value] pays under [terms], once that's within the cap and under the value; else null. */
    fun capped(
        terms: DepositTerms,
        value: BigInteger,
    ): BigInteger? = fee(terms.fee, terms.feeBps, value).takeIf { it <= cap(value) && it < value }

    /** What a rescue pays: the terms' fee alone, while that's within the cap's fixed part. */
    fun rescue(terms: DepositTerms): BigInteger? = terms.fee.takeIf { it <= CAP_FEE }

    /** What reaches the shielded balance of [shielded], once Railgun keeps its shield fee, rounded down. */
    fun afterRailgun(shielded: BigInteger): BigInteger {
        val kept = shielded * bigIntegerValueOf(RAILGUN_FEE.value.toLong()) / bigIntegerValueOf(Bps.MAX.toLong())
        return shielded - kept
    }

    /** What a deposit of [value] paying [fee] brings: `s − ⌊s × 25 / 10000⌋`, where `s = value − fee` is shielded. */
    fun arrives(
        value: BigInteger,
        fee: BigInteger,
    ): BigInteger = afterRailgun(maxOf(value - fee, bigIntegerZero))
}

// What a terms' `deposits` says, as sent: amounts are decimal strings.
@Serializable
private class WireDepositTerms(
    val contract: Address,
    val relayer: Address,
    val token: Address,
    val fee: String,
    val feeBps: Int,
    val feeExpiresAt: Long? = null,
    val minValue: String,
    val maxValidity: Long,
) {
    fun isFor(pin: DepositsPin): Boolean = contract == pin.contract && relayer == pin.relayer && token == pin.token

    // An authorization signed under these must outlive its signing again before it runs out.
    fun usable(): DepositTerms? {
        val fee = units(fee)
        val minValue = units(minValue)?.takeIf { it.signum() > 0 }
        val isUsable = feeBps in 0..Bps.MAX && maxValidity > RESIGN_BEFORE_SECONDS + CLOCK_SLACK_SECONDS
        return if (fee != null && minValue != null && isUsable) {
            DepositTerms(relayer, fee, feeBps, feeExpiresAt, minValue, maxValidity)
        } else {
            null
        }
    }
}

// A decimal string of base units that fits the contract's amounts.
private fun units(decimal: String): BigInteger? =
    decimal
        .takeIf { it.isNotEmpty() && it.length <= MAX_DECIMAL_DIGITS && it.all { c -> c in '0'..'9' } }
        ?.let { BigInteger(it) }
        ?.takeIf { it.bitLength() <= SWAP_AMOUNT_BITS }

private val wireJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

/** How long an authorization is valid for once signed, where the relayer takes that long. */
internal const val VALIDITY_SECONDS = 60 * 60L

/** How far this device's clock and the relayer's may differ. */
internal const val CLOCK_SLACK_SECONDS = 60L

/** A request is signed again once its `validBefore` is less than this away: the relayer takes none with 15 min left. */
internal const val RESIGN_BEFORE_SECONDS = 16 * 60L

// 2 USDC, in its base units.
private const val CAP_FEE_UNITS = 2_000_000L

// 2^120 has 37 digits.
private const val MAX_DECIMAL_DIGITS = 40
