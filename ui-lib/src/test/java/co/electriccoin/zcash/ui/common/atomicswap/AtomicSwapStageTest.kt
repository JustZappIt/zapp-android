// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import xyz.justzappit.offramp.atomicswap.AtomicSwapActivity
import xyz.justzappit.offramp.atomicswap.AtomicSwapRecord
import xyz.justzappit.offramp.atomicswap.AtomicSwapStep
import xyz.justzappit.offramp.atomicswap.AtomicSwapWait
import xyz.justzappit.offramp.atomicswap.SwapQuote
import kotlin.test.Test
import kotlin.test.assertEquals

class AtomicSwapStageTest {
    @Test
    fun `a swap is opening until it shows on-chain`() {
        assertEquals(AtomicSwapStage.OPENING, AtomicSwapStage.of(AtomicSwapState(record = record())))
        assertEquals(AtomicSwapStage.OPENING, AtomicSwapStage.of(state(AtomicSwapWait.OPENING)))
    }

    @Test
    fun `a slow step in flight names itself`() {
        val state = state(AtomicSwapWait.CONFIRMING)

        fun during(activity: AtomicSwapActivity) = AtomicSwapStage.of(state.copy(activity = activity))

        assertEquals(AtomicSwapStage.DEPOSITING, during(AtomicSwapActivity.DEPOSITING))
        assertEquals(AtomicSwapStage.CLAIMING, during(AtomicSwapActivity.CLAIMING))
        assertEquals(AtomicSwapStage.CLAIMING, during(AtomicSwapActivity.PAYING_OUT))
        assertEquals(AtomicSwapStage.REFUNDING, during(AtomicSwapActivity.SWEEPING))
    }

    @Test
    fun `a deposit that went out waits for confirmations, even before the loop has looked`() {
        assertEquals(AtomicSwapStage.CONFIRMING, AtomicSwapStage.of(state(AtomicSwapWait.CONFIRMING)))
        assertEquals(AtomicSwapStage.CONFIRMING, AtomicSwapStage.of(state(AtomicSwapWait.DEPOSIT_UNSETTLED)))
        assertEquals(
            AtomicSwapStage.CONFIRMING,
            AtomicSwapStage.of(AtomicSwapState(record = record().copy(depositAttempted = true, depositTxId = "0xab"))),
        )
    }

    private fun state(reason: AtomicSwapWait) =
        AtomicSwapState(record = record(), wait = AtomicSwapStep.Waiting(reason, t0 = 100, t1 = 200))

    private fun record() =
        AtomicSwapRecord(
            index = 0,
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
                    expiresAt = 1_790_000_300,
                ),
            swapId = "0x5c",
            zcashHeight = 4_200_000,
            acceptedAt = 1_790_000_000,
        )
}
