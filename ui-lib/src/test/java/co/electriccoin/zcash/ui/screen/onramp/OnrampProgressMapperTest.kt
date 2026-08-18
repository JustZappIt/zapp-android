// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.onramp

import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.onramp.OnrampDestination
import xyz.justzappit.offramp.onramp.OnrampFailureCode
import xyz.justzappit.offramp.onramp.OnrampPhase
import xyz.justzappit.offramp.onramp.OnrampStatus
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryStatus
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
    fun `a Base order ends at the USDC step with every one of them complete`() {
        val mapped = mapOnrampProgress(completed())

        assertEquals(
            listOf(
                OnrampVisibleStep.ORDER_PLACED,
                OnrampVisibleStep.MERCHANT_MATCHED,
                OnrampVisibleStep.PAY_MERCHANT,
                OnrampVisibleStep.PAYMENT_CONFIRMED,
                OnrampVisibleStep.RECEIVING_USDC,
                OnrampVisibleStep.USDC_RECEIVED,
            ),
            mapped.map { it.step },
        )
        assertEquals(mapped.size, mapped.count { it.state == OnrampStepState.COMPLETED })
    }

    @Test
    fun `Zcash delivery adds conversion steps after Base settlement`() {
        val completed = completed()
        val converting =
            mapOnrampProgress(
                status = completed,
                delivery = OnrampZecDeliveryStatus.AwaitingZec(AMOUNT),
                destination = OnrampDestination.ZCASH,
            )

        assertEquals(OnrampStepState.COMPLETED, converting[OnrampVisibleStep.USDC_RECEIVED.ordinal].state)
        assertEquals(OnrampStepState.IN_PROGRESS, converting[OnrampVisibleStep.CONVERTING_TO_ZEC.ordinal].state)
        assertEquals(OnrampStepState.PENDING, converting[OnrampVisibleStep.ZEC_RECEIVED.ordinal].state)

        val delivered =
            mapOnrampProgress(
                status = completed,
                delivery = OnrampZecDeliveryStatus.Delivered(AMOUNT, "0.019", null),
                destination = OnrampDestination.ZCASH,
            )

        assertEquals(delivered.size, delivered.count { it.state == OnrampStepState.COMPLETED })
    }

    @Test
    fun `a refund fails the conversion step rather than completing it`() {
        val mapped =
            mapOnrampProgress(
                status = completed(),
                delivery =
                    OnrampZecDeliveryStatus.RefundedToBase(
                        inputUsdc = AMOUNT,
                        refundedUsdc = AMOUNT,
                        baseAccount = Address.parse(RECIPIENT),
                    ),
                destination = OnrampDestination.ZCASH,
            )

        assertEquals(OnrampStepState.COMPLETED, mapped[OnrampVisibleStep.USDC_RECEIVED.ordinal].state)
        assertEquals(OnrampStepState.FAILED, mapped[OnrampVisibleStep.CONVERTING_TO_ZEC.ordinal].state)
        assertEquals(OnrampStepState.PENDING, mapped[OnrampVisibleStep.ZEC_RECEIVED.ordinal].state)
    }

    private fun completed() =
        OnrampStatus.Completed(
            id = ID,
            orderId = ORDER_ID,
            netUsdc = AMOUNT,
            fiatAmount = Usdc6.ofMicros(99_999_934),
            paidTx = null,
            recipientAddress = Address.parse(RECIPIENT),
        )

    private companion object {
        const val ID = "00000000-0000-4000-8000-000000000000"
        const val ORDER_ID = "659007"
        const val RECIPIENT = "0x0000000000000000000000000000000000000001"
        val AMOUNT: Usdc6 = Usdc6.ofMicros(910_153)
    }
}
