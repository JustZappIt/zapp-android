package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.model.Zatoshi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BridgeAffordabilityTest {
    private val required = Zatoshi(180_000_000L)
    private val reserve = ZEC_BRIDGE_FEE_RESERVE.value
    private val plentiful = Zatoshi(10_000_000_000L)

    @Test
    fun `sufficient when spendable covers the deposit plus the fee reserve`() {
        assertTrue(isSpendableZecSufficientForBridge(Zatoshi(required.value + reserve), required))
        assertTrue(isSpendableZecSufficientForBridge(Zatoshi(required.value + reserve + 1), required))
    }

    @Test
    fun `insufficient by exactly the network fee`() {
        assertFalse(isSpendableZecSufficientForBridge(required, required))
        assertFalse(isSpendableZecSufficientForBridge(Zatoshi(required.value + reserve - 1), required))
    }

    @Test
    fun `guard predicate rejects a deposit-only balance`() {
        assertFalse(isSpendableZecSufficientForBridge(required, required))
        assertTrue(isSpendableZecSufficientForBridge(plentiful, required))
    }

    @Test
    fun `gate flips as spendable changes around the threshold`() {
        val below = evaluateBridgeGate(hasEnteredAmount = true, requiredZec = required, spendableZec = required)
        assertFalse(below.canSubmit)
        assertTrue(below.isInsufficient)

        val atThreshold =
            evaluateBridgeGate(
                hasEnteredAmount = true,
                requiredZec = required,
                spendableZec = Zatoshi(required.value + reserve),
            )
        assertTrue(atThreshold.canSubmit)
        assertFalse(atThreshold.isInsufficient)
    }

    @Test
    fun `no estimate is never submittable and never red`() {
        val gate = evaluateBridgeGate(hasEnteredAmount = true, requiredZec = null, spendableZec = plentiful)
        assertFalse(gate.canSubmit)
        assertFalse(gate.isInsufficient)
    }

    @Test
    fun `no entered amount is never submittable`() {
        val gate = evaluateBridgeGate(hasEnteredAmount = false, requiredZec = required, spendableZec = plentiful)
        assertFalse(gate.canSubmit)
        assertFalse(gate.isInsufficient)
    }

    @Test
    fun `resolved affordable estimate becomes submittable`() {
        val gate =
            evaluateBridgeGate(
                hasEnteredAmount = true,
                requiredZec = required,
                spendableZec = Zatoshi(required.value + reserve),
            )
        assertTrue(gate.canSubmit)
        assertFalse(gate.isInsufficient)
    }

    @Test
    fun `estimate reserves the fee in spendableZecRequired`() {
        val estimate = OfframpTopUpEstimate(requiredZec = required, estimatedDurationSeconds = 600)
        assertEquals(Zatoshi(required.value + ZEC_BRIDGE_FEE_RESERVE.value), estimate.spendableZecRequired)
        assertEquals(ZEC_BRIDGE_FEE_RESERVE, estimate.feeReserveZec)
    }
}
