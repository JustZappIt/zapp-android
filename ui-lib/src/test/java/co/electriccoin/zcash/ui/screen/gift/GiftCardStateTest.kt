// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GiftCardStateTest {
    @Test
    fun `ready card has the same exit transition as the details screen`() {
        assertEquals(GiftCardBackAction.EXIT_FLOW, GiftCardStage.DETAILS.backAction)
        assertEquals(GiftCardBackAction.EXIT_FLOW, GiftCardStage.READY.backAction)
        assertEquals(GiftCardBackAction.EXIT_FLOW, GiftCardStage.UNAVAILABLE.backAction)
        assertTrue(state(stage = GiftCardStage.READY).isBackEnabled)
        assertTrue(state(stage = GiftCardStage.UNAVAILABLE).isBackEnabled)
    }

    @Test
    fun `in-flight stages continue to block navigation`() {
        listOf(GiftCardStage.PREPARING, GiftCardStage.FUNDING).forEach { stage ->
            assertEquals(GiftCardBackAction.BLOCK, stage.backAction)
            assertFalse(state(stage = stage).isBackEnabled)
        }
    }

    @Test
    fun `continue accepts only an exact positive in-range gift amount`() {
        assertTrue(state(amount = BigDecimal("0.00000001")).canContinue)
        assertTrue(state(amount = BigDecimal("21000000")).canContinue)

        listOf(
            null,
            BigDecimal.ZERO,
            BigDecimal("-1"),
            BigDecimal("0.000000001"),
            BigDecimal("21000000.00000001"),
        ).forEach { amount ->
            assertFalse(state(amount = amount).canContinue)
        }
    }

    private fun state(
        stage: GiftCardStage = GiftCardStage.DETAILS,
        amount: BigDecimal? = BigDecimal.ONE,
    ) = GiftCardState(
        stage = stage,
        amount =
            NumberTextFieldState(
                innerState = amount?.let(NumberTextFieldInnerState::fromAmount) ?: NumberTextFieldInnerState(),
                onValueChange = {},
            ),
        spendableBalance = null,
        message = "",
        messageGraphemes = 0,
        expiry = GiftExpiry.NEVER,
        quote = null,
        previewAmount = null,
        fiat = null,
        link = null,
        isAuthenticating = false,
        error = null,
        pinVerify = null,
        onAmountChange = {},
        onMessageChange = {},
        onExpiryChange = {},
        onContinue = {},
        onConfirm = {},
        onShare = {},
        onBack = {},
        onOpenSavedCards = null,
    )
}
