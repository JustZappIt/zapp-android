// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.orchestrator

import xyz.justzappit.evm.abi.Selector4

/**
 * Two-layer revert decoder:
 *
 * 1. [explain] returns a [KnownRevertReason] for selectors we want to surface with a
 *    user-actionable, localised message. Curated against the PAY flow.
 * 2. [sdkName] / [sdkMessage] fall through to the wholesale [KnownContractErrors] table
 *    (generated from `p2pdotme-sdk/src/contracts/errors.ts` + `error-messages.ts`).
 *
 * The orchestrator usually wants all three at once: call [lookup] for a single map probe.
 */
object KnownReverts {
    data class Lookup(
        val reason: KnownRevertReason?,
        val sdkName: String?,
        val sdkMessage: String?,
    )

    /**
     * The selectors that have a curated [KnownRevertReason]. Exposed so the wholesale-table
     * coverage test can iterate over the actual source of truth — adding a CURATED entry
     * automatically extends test coverage, instead of silently bypassing it.
     */
    val curatedSelectors: Set<Selector4> get() = CURATED.keys

    private val CURATED: Map<Selector4, KnownRevertReason> =
        mapOf(
            Selector4.fromHex("0x91da284f") to KnownRevertReason.BuyOrderAmountExceedsLimit,
            Selector4.fromHex("0x412dd2b1") to KnownRevertReason.InsufficientReputation,
            Selector4.fromHex("0xf42e41a1") to KnownRevertReason.OrderAmountExceedsLimit,
            Selector4.fromHex("0xbba2edf9") to KnownRevertReason.SellAmountExceedsFiatLimit,
            Selector4.fromHex("0x02a6fdd2") to KnownRevertReason.CurrencyNotSupported,
            Selector4.fromHex("0xebb6f34b") to KnownRevertReason.UserIsBlacklisted,
            Selector4.fromHex("0x4bbac5de") to KnownRevertReason.ExchangeNotOperational,
            Selector4.fromHex("0x5d04ff4c") to KnownRevertReason.NotEnoughEligibleMerchants,
            Selector4.fromHex("0xc56873ba") to KnownRevertReason.OrderExpired,
            Selector4.fromHex("0xc1654697") to KnownRevertReason.UpiAlreadySent,
            Selector4.fromHex("0xaa60ec26") to KnownRevertReason.InvalidOrderUpi,
            Selector4.fromHex("0x6b1b90b4") to KnownRevertReason.OrderNotAccepted,
            // Three Diamond variants for "USDC transferFrom failed" collapse to one user-facing reason.
            Selector4.fromHex("0x149f9fca") to KnownRevertReason.UsdcTransferFailed,
            Selector4.fromHex("0x47bfece5") to KnownRevertReason.UsdcTransferFailed,
            Selector4.fromHex("0x279bbc0c") to KnownRevertReason.UsdcTransferFailed,
            Selector4.fromHex("0xea8e4eb5") to KnownRevertReason.NotAuthorized,
        )

    // A standalone 4-byte selector embedded in a bundler error message, e.g. the ERC-4337 bundler
    // reports an on-chain revert as "...reverted during simulation with reason: 0xea8e4eb5". The
    // negative lookahead avoids matching the leading 4 bytes of a longer hex blob (e.g. an address).
    private val SELECTOR_IN_MESSAGE = Regex("0x[0-9a-fA-F]{8}(?![0-9a-fA-F])")

    /** One map probe returning the curated reason + SDK name + SDK message together. */
    fun lookup(selector: Selector4?): Lookup {
        val entry = KnownContractErrors.entryFor(selector)
        return Lookup(
            reason = selector?.let { CURATED[it] },
            sdkName = entry?.name,
            sdkMessage = entry?.message,
        )
    }

    fun explain(selector: Selector4?): KnownRevertReason? = lookup(selector).reason

    fun sdkName(selector: Selector4?): String? = lookup(selector).sdkName

    fun sdkMessage(selector: Selector4?): String? = lookup(selector).sdkMessage

    /**
     * Extracts a 4-byte revert selector from a bundler/JSON-RPC error message, if one is present.
     * ERC-4337 reverts surface as an [xyz.justzappit.evm.rpc.RpcException.Unknown] message rather than a
     * structured `ExecutionReverted`, so this lets the orchestrator recover the selector and map it
     * through [explain] / [sdkName] just like a node-level revert.
     */
    fun selectorFromMessage(message: String?): Selector4? =
        message?.let { SELECTOR_IN_MESSAGE.find(it)?.value?.let(Selector4::fromHex) }
}
