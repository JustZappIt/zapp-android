package co.electriccoin.zcash.ui.screen.reviewtransaction

import co.electriccoin.zcash.ui.design.component.ButtonStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReviewTransactionOrchardPrivacyWarningTest {
    @Test
    fun noOrchardSpendShowsNoWarning() {
        assertNull(orchardPrivacyWarningState(usesOrchardInputs = false))
    }

    @Test
    fun orchardSpendShowsWarningWithFigmaCopy() {
        val warning = orchardPrivacyWarningState(usesOrchardInputs = true)
        assertEquals("This send requires spending Orchard funds", warning?.title)
        assertEquals(
            "We recommend migrating your funds first to avoid leaking the transaction amount on-chain.",
            warning?.body,
        )
    }

    @Test
    fun noOrchardSpendKeepsDefaultButtonStyle() {
        assertNull(orchardPrivacyWarningButtonStyle(usesOrchardInputs = false))
    }

    @Test
    fun orchardSpendSwitchesToDestructiveButtonStyle() {
        assertEquals(ButtonStyle.DESTRUCTIVE1, orchardPrivacyWarningButtonStyle(usesOrchardInputs = true))
    }
}
