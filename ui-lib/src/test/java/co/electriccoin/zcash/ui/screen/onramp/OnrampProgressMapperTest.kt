// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.onramp

import xyz.justzappit.offramp.onramp.OnrampFailureCode
import xyz.justzappit.offramp.onramp.OnrampPhase
import xyz.justzappit.offramp.onramp.OnrampStatus
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals

class OnrampProgressMapperTest {
    @Test
    fun `awaiting settlement completes payment confirmation and marks receiving active`() {
        val mapped = mapOnrampProgress(OnrampStatus.AwaitingSettlement(ID, ORDER_ID))

        assertEquals(OnrampStepState.COMPLETED, mapped[3].state)
        assertEquals(OnrampStepState.IN_PROGRESS, mapped[4].state)
        assertEquals(OnrampStepState.PENDING, mapped[5].state)
    }

    @Test
    fun `failure marks its visible step failed without completing later steps`() {
        val mapped =
            mapOnrampProgress(
                OnrampStatus.Failed(
                    code = OnrampFailureCode.UPSTREAM_FAILED,
                    phase = OnrampPhase.AWAITING_PAYMENT,
                    id = ID,
                    orderId = ORDER_ID,
                ),
            )

        assertEquals(OnrampStepState.FAILED, mapped[2].state)
        assertEquals(OnrampStepState.PENDING, mapped[3].state)
    }

    @Test
    fun `an order nobody took fails at the merchant step`() {
        val mapped =
            mapOnrampProgress(
                OnrampStatus.Failed(
                    code = OnrampFailureCode.NO_MERCHANT,
                    phase = OnrampPhase.EXPIRED,
                    id = ID,
                    orderId = ORDER_ID,
                ),
            )

        assertEquals(OnrampStepState.FAILED, mapped[1].state)
    }

    @Test
    fun `a lapsed payment window fails at the pay step, not the merchant step`() {
        val mapped =
            mapOnrampProgress(
                OnrampStatus.Failed(
                    code = OnrampFailureCode.ORDER_EXPIRED,
                    phase = OnrampPhase.EXPIRED,
                    id = ID,
                    orderId = ORDER_ID,
                ),
            )

        assertEquals(OnrampStepState.COMPLETED, mapped[1].state)
        assertEquals(OnrampStepState.FAILED, mapped[2].state)
    }

    @Test
    fun `completion marks every visible step complete`() {
        val mapped =
            mapOnrampProgress(
                OnrampStatus.Completed(
                    id = ID,
                    orderId = ORDER_ID,
                    netUsdc = Usdc6.ofMicros(910_153),
                    fiatAmount = Usdc6.ofMicros(99_999_934),
                    paidTx = null,
                ),
            )

        assertEquals(OnrampVisibleStep.entries.size, mapped.count { it.state == OnrampStepState.COMPLETED })
    }

    private companion object {
        const val ID = "00000000-0000-4000-8000-000000000000"
        const val ORDER_ID = "659007"
    }
}
