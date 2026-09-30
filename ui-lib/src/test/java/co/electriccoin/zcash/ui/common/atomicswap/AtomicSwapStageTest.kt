// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import co.electriccoin.zcash.ui.screen.privateusd.toUsd
import xyz.justzappit.offramp.atomicswap.AtomicSwapActivity
import xyz.justzappit.offramp.atomicswap.AtomicSwapStep
import xyz.justzappit.offramp.atomicswap.AtomicSwapWait
import xyz.justzappit.offramp.atomicswap.SwapDeposit
import xyz.justzappit.offramp.atomicswap.ZcashTransaction
import xyz.justzappit.offramp.atomicswap.ZcashTxId
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
    fun `a sweep home that is out and waiting for confirmations is still the refund`() {
        assertEquals(AtomicSwapStage.REFUNDING, AtomicSwapStage.of(state(AtomicSwapWait.REFUNDING)))
    }

    @Test
    fun `a deposit that went out waits for confirmations, even before the loop has looked`() {
        assertEquals(AtomicSwapStage.CONFIRMING, AtomicSwapStage.of(state(AtomicSwapWait.CONFIRMING)))
        assertEquals(AtomicSwapStage.CONFIRMING, AtomicSwapStage.of(state(AtomicSwapWait.DEPOSIT_UNSETTLED)))
        assertEquals(
            AtomicSwapStage.CONFIRMING,
            AtomicSwapStage.of(AtomicSwapState(record = record().copy(deposit = SwapDeposit.Kept(DEPOSIT)))),
        )
    }

    private fun state(reason: AtomicSwapWait) =
        AtomicSwapState(record = record(), wait = AtomicSwapStep.Waiting(reason, t0 = 100, t1 = 200))

    private fun record() = toUsd(index = 0, at = 1_790_000_000, outcome = null)

    private companion object {
        val DEPOSIT = ZcashTransaction(ZcashTxId.parse("ab".repeat(32)), "00", 4_200_040)
    }
}
