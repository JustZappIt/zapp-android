// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapState
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapTestnet
import co.electriccoin.zcash.ui.common.privateusd.DollarRate
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSendRecord
import co.electriccoin.zcash.ui.design.util.StringResource
import xyz.justzappit.offramp.atomicswap.AtomicSwapOutcome
import xyz.justzappit.offramp.atomicswap.AtomicSwapRecord
import xyz.justzappit.offramp.atomicswap.NothingSentCause
import xyz.justzappit.offramp.atomicswap.SwapQuote
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrivateUsdActivityTest {
    private val opened = mutableListOf<String>()
    private val activity =
        PrivateUsdActivity(
            deployment = AtomicSwapTestnet.deployment,
            onOpenConversion = { opened += "conversion" },
            onOpenUrl = { opened += it },
        )

    @Test
    fun `newest first, with conversions that sent nothing left out`() {
        val rows =
            activity.of(
                swaps =
                    listOf(
                        swap(index = 0, at = 100, AtomicSwapOutcome.Paid),
                        swap(index = 1, at = 200, AtomicSwapOutcome.NothingSent(NothingSentCause.QUOTE_EXPIRED)),
                    ),
                sends = listOf(send(at = 300)),
                current = AtomicSwapState(),
                rate = null,
            )

        assertEquals(
            listOf(R.string.private_usd_activity_withdrew, R.string.private_usd_activity_converted),
            rows.map { (it.title as StringResource.ByResource).resource },
        )
    }

    @Test
    fun `a paid conversion links its payout on the explorer`() {
        val rows =
            activity.of(
                swaps = listOf(swap(index = 0, at = 10, AtomicSwapOutcome.Paid).copy(payoutTx = "0xpay")),
                sends = emptyList(),
                current = AtomicSwapState(),
                rate = null,
            )

        assertEquals("0xpay", rows.single().txHash)
        assertEquals(AtomicSwapTestnet.deployment.explorerTxUrl + "0xpay", rows.single().txUrl)
    }

    @Test
    fun `the amounts show in the user's currency beside the dollars only when it isn't the dollar`() {
        val swaps = listOf(swap(index = 0, at = 10, AtomicSwapOutcome.Paid))
        val sends = listOf(send(at = 20))

        val inRupees = activity.of(swaps, sends, AtomicSwapState(), DollarRate("₹", BigDecimal("83.5")))
        val inDollars = activity.of(swaps, sends, AtomicSwapState(), rate = null)

        assertTrue(inRupees.all { it.local != null })
        assertTrue(inDollars.all { it.local == null })
    }

    @Test
    fun `a send opens its transaction, and only the latest conversion opens its progress`() {
        val latest = swap(index = 2, at = 50, outcome = null)
        val rows =
            activity.of(
                swaps = listOf(swap(index = 0, at = 10, AtomicSwapOutcome.Paid), latest),
                sends = listOf(send(at = 5)),
                current = AtomicSwapState(record = latest),
                rate = null,
            )

        rows.forEach { it.onClick?.invoke() }
        assertEquals(listOf("conversion", AtomicSwapTestnet.deployment.explorerTxUrl + "0xabc"), opened)
        assertEquals(AtomicSwapTestnet.deployment.explorerTxUrl + "0xabc", rows.last().txUrl)
        assertNotNull(rows.first().onClick)
        assertNull(rows[1].onClick)
    }

    private fun swap(
        index: Int,
        at: Long,
        outcome: AtomicSwapOutcome?,
    ) = AtomicSwapRecord(
        index = index,
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
                depositZat = 202_021,
                expiresAt = at + 300,
            ),
        swapId = "0x5c",
        zcashHeight = 4_200_000,
        acceptedAt = at,
        receives = "977550",
        outcome = outcome,
        finishedAt = outcome?.let { at },
    )

    private fun send(at: Long) =
        PrivateUsdSendRecord(
            txHash = "0xabc",
            withdraw = true,
            token = "0x5764D0044bef5AA839E0dDafE2073421101B9Ed8",
            amount = "1000000",
            to = "0x1c7f9a756b08753cf8da94d394659134bb8c5539",
            sentAt = at,
        )
}
