// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.convert

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapQuote
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapTestnet
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdTokens
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.util.StringResource
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.offramp.atomicswap.AtomicSwapOffer
import xyz.justzappit.offramp.atomicswap.SwapQuote
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrivateUsdConvertTermsTest {
    private val deployment = AtomicSwapTestnet.deployment
    private val terms =
        PrivateUsdConvertTerms(
            deployment,
            checkNotNull(PrivateUsdTokens.find(deployment.railgunNetwork, deployment.config.token.checksumHex)),
        )

    @Test
    fun `the presets read as dollars`() {
        assertEquals(
            listOf("$1", "$5", "$10", "$20"),
            terms.amounts.take(terms.presets.size).map { (it as StringResource.ByString).value },
        )
        assertEquals(terms.presets.size, terms.custom)
    }

    @Test
    fun `a custom amount is whole dollars within the maker's limit`() {
        assertEquals(7, terms.units(custom("7")))
        assertEquals(20, terms.units(custom("20")))
        assertNull(terms.units(custom("7.5")))
        assertNull(terms.units(custom("0")))
        assertNull(terms.units(custom("21")))
        assertNull(terms.units(NumberTextFieldInnerState()))
    }

    @Test
    fun `the deposit and its network fee must fit what the wallet can spend`() {
        val ready = ConvertQuote.Ready(1, AtomicSwapQuote(offer(depositZat = 202_021), depositFeeZat = 15_000))

        assertFalse(terms.isShort(ready, Zatoshi(217_021)))
        assertTrue(terms.isShort(ready, Zatoshi(217_020)))
        assertFalse(terms.isShort(ready, null))
    }

    private fun custom(dollars: String) = NumberTextFieldInnerState.fromAmount(BigDecimal(dollars))

    private fun offer(depositZat: Long) =
        AtomicSwapOffer(
            index = 0,
            units = 1,
            quote =
                SwapQuote(
                    quoteId = "0x22",
                    maker = "0x09eD1F966745Be18C711C346242c0974DAd7c3e5",
                    makerShare = "0x0a",
                    makerProof = "0x06",
                    chainId = 11_155_111,
                    contract = "0x32CE55D00E6184c385E44e6b20b76d3a8407E809",
                    token = "0x5764D0044bef5AA839E0dDafE2073421101B9Ed8",
                    amount = "1000000",
                    depositZat = depositZat,
                    expiresAt = 1_790_000_300,
                ),
            relayerFee = bigIntegerValueOf(20_000),
            receives = bigIntegerValueOf(977_550),
        )
}
