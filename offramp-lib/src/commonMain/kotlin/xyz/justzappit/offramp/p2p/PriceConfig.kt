// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import xyz.justzappit.evm.abi.AbiDecoder
import xyz.justzappit.evm.math.BigDecimal
import xyz.justzappit.evm.math.DecimalRounding
import xyz.justzappit.evm.math.decimalDivide
import xyz.justzappit.evm.math.decimalMultiply
import xyz.justzappit.evm.math.decimalSetScale
import xyz.justzappit.evm.math.decimalStripTrailingZeros

/**
 * Both sides of the corridor's price, expressed in the same 6-decimal unit as USDC by the diamond
 * contract (e.g. `sellPrice = 89_178_176` ⇒ 1 USDC ≈ 89.178176 fiat). Wrapping in [Usdc6] prevents
 * accidentally treating the value as a fiat or USDC whole-token quantity at any callsite.
 *
 * The two are not the same number and are never interchangeable: on INR mainnet the buy side sits
 * around 100.46 and the sell side around 96.52, and pricing a purchase off the sell rate would
 * quote the user roughly 4% wrong in the merchant's favour.
 */
data class PriceConfig(
    val sellPrice: Usdc6,
    val buyPrice: Usdc6 = Usdc6.ZERO,
) {
    fun sellPriceAsRate(): BigDecimal = decimalStripTrailingZeros(sellPrice.whole)

    fun fiatForUsdc(usdcAmount: BigDecimal, fiatScale: Int = DEFAULT_FIAT_DISPLAY_SCALE): BigDecimal =
        decimalSetScale(
            decimalMultiply(usdcAmount, sellPriceAsRate()),
            fiatScale,
            DecimalRounding.HALF_UP,
        )

    fun usdcForFiat(fiatAmount: BigDecimal, usdcScale: Int = DEFAULT_USDC_DISPLAY_SCALE): BigDecimal =
        decimalDivide(fiatAmount, sellPriceAsRate(), usdcScale, DecimalRounding.HALF_UP)

    companion object {
        private const val DEFAULT_FIAT_DISPLAY_SCALE = 2
        private const val DEFAULT_USDC_DISPLAY_SCALE = 4
    }
}

object PriceConfigDecoder {
    /**
     * Decodes `getPriceConfig(bytes32)` — `(buyPrice, sellPrice, buyPriceOffset, baseSpread)`. The
     * last two are the exchange's own inputs to those first two and are not surfaced.
     */
    fun decode(returnData: ByteArray): PriceConfig {
        val d = AbiDecoder(returnData)
        d.requireWords(FOUR_WORDS)
        return PriceConfig(
            sellPrice = Usdc6(d.uint(SLOT_SELL_PRICE)),
            buyPrice = Usdc6(d.uint(SLOT_BUY_PRICE)),
        )
    }

    private const val SLOT_BUY_PRICE = 0
    private const val SLOT_SELL_PRICE = 1
    private const val FOUR_WORDS = 4
}
