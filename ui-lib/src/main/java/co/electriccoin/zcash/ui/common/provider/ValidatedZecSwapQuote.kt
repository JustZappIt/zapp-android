// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.ZecSwapAsset
import co.electriccoin.zcash.ui.common.model.near.requireQuoteMatchesUserAmount
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryCheckpoint
import xyz.justzappit.offramp.p2p.Usdc6
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Instant

/** The only fields of a validated USDC → ZEC quote worth keeping: enough to send, poll, and resume. */
internal data class ValidatedZecSwapQuote(
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
internal val ValidatedZecSwapQuote.costBasisPoints: Int
    get() =
        inputUsd
            .subtract(outputUsd)
            .max(BigDecimal.ZERO)
            .multiply(BigDecimal(OnrampZecDeliveryCheckpoint.MAX_BPS))
            .divide(inputUsd, 0, RoundingMode.CEILING)
            .toInt()

/**
 * Fail-closed echo check for every Base-USDC → ZEC quote, shared by the onramp delivery leg and the
 * offramp pull-back refund. Both bypass `RequestSwapQuoteUseCase`'s validateQuote layer and both
 * send real funds to [SwapQuote.depositAddress], so the echo is asserted before the transfer is
 * authorized. Asset ids are deliberately not compared: 1-Click rewrites them during routing, so the
 * echoed id carries no authorization.
 */
internal fun validateZecSwapQuote(
    quote: SwapQuote,
    amount: Usdc6,
    account: Address,
    zcashRecipient: String,
    slippageTolerancePercent: BigDecimal,
    now: Instant,
): ValidatedZecSwapQuote {
    require(quote.mode == SwapMode.EXACT_INPUT) { "Swap quote is not exact-input" }
    requireQuoteMatchesUserAmount(quote.amountInFormatted, amount.whole, quote.originAsset.decimals)
    requireEvmAddressMatches(account, quote.refundAddress.address, "refund")
    require(quote.destinationAddress.address == zcashRecipient) { "Swap quote destination mismatch" }
    require(quote.amountOut.signum() > 0) { "Swap quote has non-positive ZEC output" }
    require(quote.amountInUsd.signum() > 0) { "Swap quote has non-positive input value" }
    require(quote.amountOutUsd.signum() > 0) { "Swap quote has non-positive output value" }
    require(quote.slippage.compareTo(slippageTolerancePercent) == 0) { "Swap quote slippage mismatch" }
    require(
        quote.deadline.toEpochMilliseconds() > now.toEpochMilliseconds() + ZEC_QUOTE_EXPIRY_MARGIN_MILLIS
    ) { "Swap quote does not leave enough time to submit safely" }
    return ValidatedZecSwapQuote(
        depositAddress = Address.parse(quote.depositAddress.address),
        zcashRecipient = zcashRecipient,
        deadlineMillis = quote.deadline.toEpochMilliseconds(),
        outputZec = quote.amountOutFormatted.stripTrailingZeros().toPlainString(),
        inputUsd = quote.amountInUsd,
        outputUsd = quote.amountOutUsd,
    )
}

/**
 * The status endpoint is keyed only by deposit address, so its echoed quote is re-checked against
 * the checkpoint before its terminal verdict is believed — otherwise a substituted route could be
 * read as this order's delivery.
 */
internal fun validateZecSwapStatus(
    quote: SwapQuote,
    checkpoint: OnrampZecDeliveryCheckpoint,
) {
    val amount = Usdc6(BigInteger(checkpoint.usdcMicros))
    require(quote.mode == SwapMode.EXACT_INPUT) { "Swap status is not exact-input" }
    requireQuoteMatchesUserAmount(quote.amountInFormatted, amount.whole, quote.originAsset.decimals)
    requireEvmAddressMatches(Address.parse(checkpoint.baseAccount), quote.refundAddress.address, "refund")
    require(quote.destinationAddress.address == checkpoint.zcashRecipient) { "Swap status destination mismatch" }
    requireEvmAddressMatches(
        Address.parse(checkpoint.depositAddress.orEmpty()),
        quote.depositAddress.address,
        "deposit",
    )
}

private fun requireEvmAddressMatches(expected: Address, actual: String, name: String) {
    require(Address.parseOrNull(actual) == expected) { "Swap $name address mismatch" }
}

internal const val ZEC_QUOTE_EXPIRY_MARGIN_MILLIS = 60_000L

/** Picks the ZEC entry from the 1-Click supported-token catalog; throws if missing. */
internal fun List<SwapAsset>.zecAsset(): SwapAsset =
    filterIsInstance<ZecSwapAsset>().firstOrNull()
        ?: error("ZEC is not in the 1-Click supported-token list")

/**
 * Picks the USDC entry by matching the configured on-chain address against the 1-Click asset id
 * (which embeds the contract, e.g. `nep141:base-0x833589…omft.near`). Lookups by raw address keep
 * the catalog network-agnostic — no hardcoded NEP asset id per network — so adding a new chain to
 * P2pNetworks doesn't drag a new constant in here.
 */
internal fun List<SwapAsset>.usdcAsset(usdc: Address): SwapAsset =
    firstOrNull { it.assetId.contains(usdc.lowercaseHex.removePrefix("0x"), ignoreCase = true) }
        ?: error("USDC (${usdc.checksumHex}) is not in the 1-Click supported-token list")
