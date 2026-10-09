// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd

import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapTestnet
import io.mockk.MockKAnswerScope
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.offramp.atomicswap.AtomicSwapOffer
import xyz.justzappit.offramp.atomicswap.AtomicSwapOutcome
import xyz.justzappit.offramp.atomicswap.AtomicSwapRecord
import xyz.justzappit.offramp.atomicswap.NoteCommitment
import xyz.justzappit.offramp.atomicswap.ReverseFundingCost
import xyz.justzappit.offramp.atomicswap.ReversePhase
import xyz.justzappit.offramp.atomicswap.ReverseQuote
import xyz.justzappit.offramp.atomicswap.ReverseSwapRecord
import xyz.justzappit.offramp.atomicswap.SwapAcceptance
import xyz.justzappit.offramp.atomicswap.SwapEnd
import xyz.justzappit.offramp.atomicswap.SwapId
import xyz.justzappit.offramp.atomicswap.SwapQuote
import xyz.justzappit.offramp.atomicswap.SwapShare
import xyz.justzappit.offramp.p2p.Usdc6
import java.math.BigInteger

/** Conversions in both directions, in the testnet's dollar, as the stores keep them. */
internal fun toUsd(
    index: Int,
    at: Long,
    outcome: AtomicSwapOutcome?,
) = AtomicSwapRecord(
    index = index,
    quote = forwardQuote(amount = 1_000_000, depositZat = 202_021, expiresAt = at + 300),
    swapId = SwapId.parse(hex(32, 0x5c)),
    zcashHeight = 4_200_000,
    acceptedAt = at,
    receives = Usdc6.ofMicros(977_550),
    relayerFee = Usdc6.ofMicros(20_000),
    end = outcome?.let { SwapEnd(it, at) },
)

/** An offer of [requested] base units of the testnet's dollar, the relayer asking 0.02 of it. */
internal fun offer(
    index: Int,
    requested: Long,
    depositZat: Long,
    expiresAt: Long,
    receives: Long = requested,
    maxTotalZat: Long? = null,
    networkCost: Long? = null,
) = AtomicSwapOffer(
    index = index,
    requested = Usdc6.ofMicros(requested),
    quote = forwardQuote(requested, depositZat, expiresAt, networkCost),
    relayerFee = Usdc6.ofMicros(20_000),
    receives = Usdc6.ofMicros(receives),
    maxTotalZat = maxTotalZat,
)

private fun forwardQuote(
    amount: Long,
    depositZat: Long,
    expiresAt: Long,
    networkCost: Long? = null,
) = SwapQuote(
    quoteId = hex(32, 0x22),
    maker = Address.parse("0x09eD1F966745Be18C711C346242c0974DAd7c3e5"),
    makerShare = SwapShare.parse(hex(64, 0x0a)),
    makerProof = hex(64, 0x06),
    chainId = ChainId.ETHEREUM_SEPOLIA,
    contract = Address.parse("0x32CE55D00E6184c385E44e6b20b76d3a8407E809"),
    token = TEST_USD,
    amount = Usdc6.ofMicros(amount),
    depositZat = depositZat,
    expiresAt = expiresAt,
    networkCost = networkCost?.let(Usdc6::ofMicros),
)

internal fun toZec(
    index: Int,
    phase: ReversePhase,
    acceptedAt: Long? = 1_000,
    depositZat: Long = 100_000,
) = ReverseSwapRecord(
    index = index,
    deployment = AtomicSwapTestnet.deployment.swap,
    quote =
        ReverseQuote(
            SwapQuote(
                quoteId = hex(32, 1),
                maker = address(2),
                makerShare = SwapShare.parse(hex(64, 2)),
                makerProof = hex(64, 7),
                chainId = ChainId.ETHEREUM_SEPOLIA,
                contract = address(4),
                token = TEST_USD,
                amount = Usdc6.ofMicros(1_000_000),
                depositZat = depositZat,
                expiresAt = 2_000,
            ),
            address(1),
            NoteCommitment.parse(hex(32, 6)),
            2_000,
            4_000,
            6_000,
        ),
    swapId = SwapId.parse(hex(32, 8)),
    userShare = SwapShare.parse(hex(64, 1)),
    acceptance = SwapAcceptance(hex(64, 1), hex(64, 0), hex(64, 0)),
    birthday = 100,
    phase = phase,
    cost =
        ReverseFundingCost(
            Usdc6.ofMicros(1_000_000),
            railgunFee = Usdc6.ofMicros(2_500),
            broadcasterFee = Usdc6.ofMicros(250_000),
        ),
    acceptedAt = acceptedAt,
)

internal val TEST_USD = Address.parse("0x5764D0044bef5AA839E0dDafE2073421101B9Ed8")

/** The amount a mocked `quote(requested)` was asked for: mocks see a value class as the value it wraps. */
internal fun MockKAnswerScope<*, *>.requested(): Usdc6 = firstArg<Any>().let { it as? Usdc6 ?: Usdc6(it as BigInteger) }

internal fun address(value: Int) = Address.parse(hex(20, value))

internal fun hex(
    bytes: Int,
    value: Int
) = "0x" + value.toString(16).padStart(2, '0').repeat(bytes)
