// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.liveness

import xyz.justzappit.evm.abi.AbiAddress
import xyz.justzappit.evm.abi.AbiBytes
import xyz.justzappit.evm.abi.AbiBytes32
import xyz.justzappit.evm.abi.AbiDecoder
import xyz.justzappit.evm.abi.AbiEncoder
import xyz.justzappit.evm.abi.AbiString
import xyz.justzappit.evm.abi.AbiUint
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6

/** Calldata and return decoding for `ZappCheckoutIntegrator`. */
object LivenessCalls {
    fun submitAttestationCalldata(attestation: LivenessAttestation): ByteArray =
        AbiEncoder.encodeFunctionCall(
            SUBMIT_SIGNATURE,
            listOf(
                AbiBytes32(attestation.nullifier),
                AbiUint(attestation.limit),
                AbiUint(bigIntegerValueOf(attestation.expiry)),
                AbiBytes(attestation.signature),
            ),
        )

    /**
     * The integrator places a BUY on the Diamond for `msg.sender`, who is also its recipient —
     * there is no recipient parameter, so a caller can only ever buy for themselves.
     */
    fun buyUsdcCalldata(
        amount: Usdc6,
        currency: CurrencyCode,
        circleId: BigInteger,
        pubKey: String,
        preferredPaymentChannelConfigId: BigInteger,
        fiatAmountLimit: Usdc6,
    ): ByteArray =
        AbiEncoder.encodeFunctionCall(
            BUY_USDC_SIGNATURE,
            listOf(
                AbiUint(amount.micros),
                AbiEncoder.bytes32String(currency.code),
                AbiUint(circleId),
                AbiString(pubKey),
                AbiUint(preferredPaymentChannelConfigId),
                AbiUint(fiatAmountLimit.micros),
            ),
        )

    fun verifiedCalldata(user: Address): ByteArray =
        AbiEncoder.encodeFunctionCall("verified(address)", listOf(AbiAddress(user)))

    /** `min(attested limit, livenessTierCap)`, or 0 when unverified or blocked. */
    fun effectiveLimitCalldata(user: Address): ByteArray =
        AbiEncoder.encodeFunctionCall("effectiveLimit(address)", listOf(AbiAddress(user)))

    /** The per-tx cap a fresh verification is worth right now. */
    fun tierCapCalldata(): ByteArray = AbiEncoder.encodeFunctionCall("livenessTierCap()", emptyList())

    /** Placements left for this wallet today, by the integrator's UTC day. */
    fun remainingDailyCountCalldata(user: Address): ByteArray =
        AbiEncoder.encodeFunctionCall("getRemainingDailyCount(address)", listOf(AbiAddress(user)))

    fun decodeBool(returnData: ByteArray): Boolean =
        AbiDecoder(returnData).also { it.requireWords(1) }.uint(0).signum() != 0

    fun decodeUsdc6(returnData: ByteArray): Usdc6 =
        Usdc6(AbiDecoder(returnData).also { it.requireWords(1) }.uint(0))

    fun decodeUint(returnData: ByteArray): BigInteger = AbiDecoder(returnData).also { it.requireWords(1) }.uint(0)

    internal const val SUBMIT_SIGNATURE = "submitLivenessAttestation(bytes32,uint256,uint256,bytes)"
    internal const val BUY_USDC_SIGNATURE = "buyUsdc(uint256,bytes32,uint256,string,uint256,uint256)"
}
