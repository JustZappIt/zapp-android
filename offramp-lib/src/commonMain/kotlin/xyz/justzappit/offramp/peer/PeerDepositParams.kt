// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import xyz.justzappit.evm.abi.AbiAddress
import xyz.justzappit.evm.abi.AbiArg
import xyz.justzappit.evm.abi.AbiArray
import xyz.justzappit.evm.abi.AbiBool
import xyz.justzappit.evm.abi.AbiBytes
import xyz.justzappit.evm.abi.AbiBytes32
import xyz.justzappit.evm.abi.AbiEncoder
import xyz.justzappit.evm.abi.AbiInt16
import xyz.justzappit.evm.abi.AbiTuple
import xyz.justzappit.evm.abi.AbiUint
import xyz.justzappit.evm.abi.AbiUint32
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.p2p.Usdc6

/**
 * The `createDeposit` argument, typed. Every field that could be silently swapped with another of
 * the same primitive shape is a distinct type, and the invariants are checked here so a malformed
 * deposit cannot be constructed, let alone broadcast.
 *
 * Offering several currencies on one deposit is the matching-speed lever: the buyer picks the leg
 * they can pay, and every leg fills at the oracle rate with zero spread.
 */
data class PeerDepositParams(
    val token: Address,
    val amount: Usdc6,
    val platform: PeerPlatform,
    val payeeHash: PayeeHash,
    val currencies: List<PeerCurrency>,
    val gatingService: Address,
    val oracleAdapter: Address,
    val intentAmountMin: Usdc6,
    val delegate: Address = Address.ZERO,
    val intentGuardian: Address = Address.ZERO,
    val retainOnEmpty: Boolean = false,
    val spread: Bps = Bps.ZERO,
    val maxStalenessSeconds: Long = PeerNetworks.ORACLE_MAX_STALENESS_SECONDS,
) {
    init {
        require(amount > Usdc6.ZERO) { "deposit amount must be positive" }
        require(currencies.isNotEmpty()) { "deposit must offer at least one currency" }
        require(currencies.size == currencies.toSet().size) { "deposit currencies must be unique" }
        require(currencies.all { it in platform.currencies }) {
            "currency not offered on ${platform.wireName}"
        }
        require(intentAmountMin > Usdc6.ZERO) { "intent minimum must be positive" }
        require(intentAmountMin <= amount) { "intent minimum must not exceed the deposit amount" }
    }

    /** Buyers take any slice in this range, so one deposit can be filled by several of them. */
    val intentAmountMax: Usdc6 get() = amount

    fun toAbiArg(): AbiTuple =
        AbiTuple(
            listOf(
                AbiAddress(token),
                AbiUint(amount.micros),
                AbiTuple(listOf(AbiUint(intentAmountMin.micros), AbiUint(intentAmountMax.micros))),
                AbiArray(listOf(AbiBytes32(platform.paymentMethodHash.bytes))),
                AbiArray(listOf(paymentMethodDataArg())),
                AbiArray(listOf(AbiArray(currencies.map(::currencyArg)))),
                AbiAddress(delegate),
                AbiAddress(intentGuardian),
                AbiBool(retainOnEmpty),
            ),
        )

    private fun paymentMethodDataArg(): AbiArg =
        AbiTuple(
            listOf(
                AbiAddress(gatingService),
                AbiBytes32(payeeHash.bytes),
                AbiBytes(ByteArray(0)),
            ),
        )

    private fun currencyArg(currency: PeerCurrency): AbiArg =
        AbiTuple(
            listOf(
                AbiBytes32(currency.codeHash.bytes),
                // Required non-zero sentinel: EscrowV2 rejects a zero floor even with an oracle
                // attached. All pricing comes from the oracle at zero spread.
                AbiUint(MIN_CONVERSION_RATE_SENTINEL),
                AbiTuple(
                    listOf(
                        AbiAddress(oracleAdapter),
                        AbiBytes(adapterConfig(currency)),
                        AbiInt16(spread.value),
                        AbiUint32(maxStalenessSeconds),
                    ),
                ),
            ),
        )

    private fun adapterConfig(currency: PeerCurrency): ByteArray =
        AbiEncoder.encode(
            listOf(
                AbiAddress(currency.feed ?: Address.ZERO),
                AbiBool(currency.invert),
            ),
        )

    companion object {
        private val MIN_CONVERSION_RATE_SENTINEL: BigInteger = bigIntegerValueOf(1L)

        /**
         * Keeps the buyer-visible floor at the practical minimum so a small buyer can nibble a
         * large order, unless the order itself is smaller than that floor.
         */
        fun defaultIntentAmountMin(amount: Usdc6): Usdc6 =
            minOf(amount, Usdc6.ofMicros(PeerNetworks.INTENT_AMOUNT_FLOOR_MICROS))
    }
}
