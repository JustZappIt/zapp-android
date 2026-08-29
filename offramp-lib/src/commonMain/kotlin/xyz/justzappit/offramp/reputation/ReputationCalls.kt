// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.reputation

import xyz.justzappit.evm.abi.AbiAddress
import xyz.justzappit.evm.abi.AbiArray
import xyz.justzappit.evm.abi.AbiBytes
import xyz.justzappit.evm.abi.AbiBytes32
import xyz.justzappit.evm.abi.AbiDecoder
import xyz.justzappit.evm.abi.AbiEncoder
import xyz.justzappit.evm.abi.AbiString
import xyz.justzappit.evm.abi.AbiTuple
import xyz.justzappit.evm.abi.AbiUint32
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.math.div
import xyz.justzappit.evm.math.times
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.reclaim.OnChainProof

/** `rmusers(address)` — the whole of a user's reputation record on the ReputationManager. */
data class RmUser(
    val reputationPoints: BigInteger,
    val voteCount: BigInteger,
    val isBlacklisted: Boolean,
)

/** `userTxLimit(address,bytes32)` on the Diamond: what this address may buy and sell, per order. */
data class UserTxLimits(
    val buy: Usdc6,
    val sell: Usdc6,
)

/**
 * `getRpPerUsdtLimitRational(bytes32)` — reputation points per USDC of buy limit, as an exact
 * fraction, and therefore how much limit one more verification buys.
 *
 * The direction of the fraction was measured, not assumed: the same wallet at 100 RP holds a $100
 * buy limit on INR (1/1), $200 on PEN (1/2) and $300 on BRL (1/3), across ten wallets and three
 * corridors on Base mainnet 2026-08-29. So limit = points × denominator ÷ numerator, and a
 * corridor is worth stating a hint for whatever its ratio — but the hint is *only* ever a hint:
 * the number a screen shows is always the Diamond's own `userTxLimit`.
 */
data class RpPerUsdcLimit(
    val numerator: BigInteger,
    val denominator: BigInteger,
) {
    val isReadable: Boolean get() = numerator.signum() > 0 && denominator.signum() > 0

    /**
     * Micro-USDC of buy limit [points] reputation is worth in this corridor, or null when the
     * ratio is unusable (a zero on either side — an unset corridor, or a read that came back
     * empty). Null means "say nothing", never "zero".
     */
    fun limitMicrosFor(points: BigInteger): BigInteger? {
        if (!isReadable) return null
        return points * MICROS_PER_USDC * denominator / numerator
    }

    private companion object {
        val MICROS_PER_USDC: BigInteger = bigIntegerValueOf(1_000_000L)
    }
}

/**
 * Calldata and return decoding for the reputation reads. Two contracts are in play and the split
 * matters: reputation itself lives on the **ReputationManager proxy**, while the limits it buys
 * are read from the **Diamond**, which is the only place the effective number exists.
 *
 * Selectors were verified against Base mainnet on 2026-08-29. Two near-miss names cost an
 * afternoon each if reintroduced: `getReputationPerUsdcLimit` (the SDK's export, not a selector —
 * it wraps `getRpPerUsdtLimitRational`) and `getContractVersion` (unregistered; the Diamond
 * answers `Diamond: Function does not exist`, naming nothing).
 */
@Suppress("TooManyFunctions")
object ReputationCalls {
    // ---- ReputationManager proxy ----

    fun rmusersCalldata(user: Address): ByteArray =
        AbiEncoder.encodeFunctionCall("rmusers(address)", listOf(AbiAddress(user)))

    fun socialVerifiedCalldata(user: Address): ByteArray =
        AbiEncoder.encodeFunctionCall("socialVerified(address)", listOf(AbiAddress(user)))

    /** The zero-arg getter for one platform's award, e.g. `linkedInRp()`. */
    fun rpAwardCalldata(platform: SocialPlatform): ByteArray =
        AbiEncoder.encodeFunctionCall(platform.rpGetterSignature, emptyList())

    /**
     * The one write. Targets the ReputationManager **proxy**, not the Diamond; the rpHelper that
     * holds the implementation is reached from there by delegatecall.
     *
     * ```
     * socialVerify(string _socialName, Proof[] proofs)
     *   Proof             = (ClaimInfo claimInfo, SignedClaim signedClaim)
     *   ClaimInfo         = (string provider, string parameters, string context)
     *   SignedClaim       = (CompleteClaimData claim, bytes[] signatures)
     *   CompleteClaimData = (bytes32 identifier, address owner, uint32 timestampS, uint32 epoch)
     * ```
     *
     * `_socialName` is case-sensitive on chain — [SocialPlatform.onChainName], never a lowercased
     * platform key.
     */
    fun socialVerifyCalldata(platform: SocialPlatform, proofs: List<OnChainProof>): ByteArray {
        require(proofs.isNotEmpty()) { "socialVerify needs at least one proof" }
        return AbiEncoder.encodeFunctionCall(
            SOCIAL_VERIFY_SIGNATURE,
            listOf(
                AbiString(platform.onChainName),
                AbiArray(proofs.map(::proofTuple)),
            ),
        )
    }

    private fun proofTuple(proof: OnChainProof): AbiTuple =
        AbiTuple(
            listOf(
                AbiTuple(
                    listOf(
                        AbiString(proof.provider),
                        AbiString(proof.parameters),
                        AbiString(proof.context),
                    ),
                ),
                AbiTuple(
                    listOf(
                        AbiTuple(
                            listOf(
                                AbiBytes32(proof.identifier),
                                AbiAddress(proof.owner),
                                AbiUint32(proof.timestampS),
                                AbiUint32(proof.epoch),
                            ),
                        ),
                        AbiArray(proof.signatures.map(::AbiBytes)),
                    ),
                ),
            ),
        )

    // ---- Diamond ----

    fun userTxLimitCalldata(user: Address, currency: CurrencyCode): ByteArray =
        AbiEncoder.encodeFunctionCall(
            "userTxLimit(address,bytes32)",
            listOf(AbiAddress(user), AbiEncoder.bytes32String(currency.code)),
        )

    fun maxBuyTxLimitCalldata(currency: CurrencyCode): ByteArray =
        AbiEncoder.encodeFunctionCall(
            "getMaxBuyTxLimit(bytes32)",
            listOf(AbiEncoder.bytes32String(currency.code)),
        )

    fun rpPerUsdcLimitCalldata(currency: CurrencyCode): ByteArray =
        AbiEncoder.encodeFunctionCall(
            "getRpPerUsdtLimitRational(bytes32)",
            listOf(AbiEncoder.bytes32String(currency.code)),
        )

    // ---- Decoders ----

    fun decodeRmUser(returnData: ByteArray): RmUser {
        val decoder = AbiDecoder(returnData).also { it.requireWords(RM_USER_WORDS) }
        return RmUser(
            reputationPoints = decoder.uint(0),
            voteCount = decoder.uint(1),
            isBlacklisted = decoder.uint(2).signum() != 0,
        )
    }

    /**
     * The 7-tuple `socialVerified` returns, mapped by [SocialPlatform.socialVerifiedIndex]. The
     * tuple's order is *not* this enum's: index 5 is passport and index 6 is Binance, so reading
     * it positionally shows the wrong platform as verified.
     */
    fun decodeSocialVerified(returnData: ByteArray): Set<SocialPlatform> {
        val decoder =
            AbiDecoder(returnData).also { it.requireWords(SocialPlatform.SOCIAL_VERIFIED_FLAGS) }
        return SocialPlatform.entries
            .filter { decoder.uint(it.socialVerifiedIndex).signum() != 0 }
            .toSet()
    }

    fun decodeUserTxLimits(returnData: ByteArray): UserTxLimits {
        val decoder = AbiDecoder(returnData).also { it.requireWords(TX_LIMIT_WORDS) }
        return UserTxLimits(buy = Usdc6(decoder.uint(0)), sell = Usdc6(decoder.uint(1)))
    }

    fun decodeUsdc6(returnData: ByteArray): Usdc6 =
        Usdc6(AbiDecoder(returnData).also { it.requireWords(1) }.uint(0))

    fun decodeUint(returnData: ByteArray): BigInteger =
        AbiDecoder(returnData).also { it.requireWords(1) }.uint(0)

    fun decodeRpPerUsdcLimit(returnData: ByteArray): RpPerUsdcLimit {
        val decoder = AbiDecoder(returnData).also { it.requireWords(RATIONAL_WORDS) }
        return RpPerUsdcLimit(numerator = decoder.uint(0), denominator = decoder.uint(1))
    }

    internal const val SOCIAL_VERIFY_SIGNATURE =
        "socialVerify(string,((string,string,string),((bytes32,address,uint32,uint32),bytes[]))[])"

    private const val RM_USER_WORDS = 3
    private const val TX_LIMIT_WORDS = 2
    private const val RATIONAL_WORDS = 2
}
