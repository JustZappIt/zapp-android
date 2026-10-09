// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

@file:UseSerializers(LowercaseAddressSerializer::class)

package xyz.justzappit.offramp.atomicswap

import io.ktor.http.Url
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId

/** A ZecSwap deployment, kept with each reverse swap's record; what's at its default isn't written. */
@Serializable
data class SwapDeployment(
    val makerUrl: Url,
    val relayerUrl: Url,
    val rpcUrl: Url,
    val chainId: ChainId,
    val contract: Address,
    val token: Address,
    @SerialName("railgun")
    val railgunProxy: Address,
    /** The only maker whose quotes are taken. */
    val maker: Address,
    /** The only relayer payouts are signed for. */
    val relayer: Address,
    /** Confirmations, including its own block, required for escrow, locks and payouts in either direction. */
    @SerialName("confirmations")
    val escrowConfirmations: Long = DEFAULT_ESCROW_CONFIRMATIONS,
    val zcashNetwork: SwapZcashNetwork = SwapZcashNetwork.TESTNET,
    /** Confirmations the maker waits for before it counts a deposit; a sweep home waits as long before it's done. */
    val zcashConfirmations: Int = DEFAULT_ZCASH_CONFIRMATIONS,
    /** Where the maker's accepts get their Privacy Pass tokens; none pinned refuses a maker that asks for one. */
    val tokenIssuer: SwapTokenIssuer? = null,
)

/** How a forward swap's maker times its deposit. */
data class ZcashDepositTerms(
    /** The soonest `t0` a swap may have: time for the deposit to get its confirmations. */
    val minSecondsToT0: Long = MIN_SECONDS_TO_T0,
)

private const val DEFAULT_ESCROW_CONFIRMATIONS = 3L
private const val DEFAULT_ZCASH_CONFIRMATIONS = 3
