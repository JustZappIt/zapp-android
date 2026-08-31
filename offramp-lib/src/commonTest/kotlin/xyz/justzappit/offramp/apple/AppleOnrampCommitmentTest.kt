// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

import xyz.justzappit.offramp.onramp.OnrampZecDeliveryCheckpoint
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppleOnrampCommitmentTest {
    @Test
    fun `Base and ambiguous delivery funds remain committed`() {
        assertEquals(AMOUNT, checkpoint(OnrampZecDeliveryPhase.FUNDS_ON_BASE).pendingBaseCommitmentMicros)
        assertEquals(
            AMOUNT,
            checkpoint(
                phase = OnrampZecDeliveryPhase.TRANSFER_STARTING,
                transferStarted = true,
                withQuote = true,
            ).pendingBaseCommitmentMicros,
        )
    }

    @Test
    fun `a confirmed Base transfer is no longer a raw balance commitment`() {
        assertNull(
            checkpoint(
                phase = OnrampZecDeliveryPhase.AWAITING_ZEC,
                transferStarted = true,
                withQuote = true,
                baseTransactionHash = HASH,
            ).pendingBaseCommitmentMicros,
        )
    }

    private fun checkpoint(
        phase: OnrampZecDeliveryPhase,
        transferStarted: Boolean = false,
        withQuote: Boolean = false,
        baseTransactionHash: String? = null,
    ) = OnrampZecDeliveryCheckpoint(
        phase = phase,
        usdcMicros = AMOUNT,
        baseAccount = ACCOUNT,
        zcashRecipient = if (withQuote) "u1recipient" else null,
        depositAddress = if (withQuote) DEPOSIT else null,
        quoteDeadlineMillis = if (withQuote) 1L else null,
        transferStarted = transferStarted,
        baseTransactionHash = baseTransactionHash,
    )

    private companion object {
        const val AMOUNT = "5000000"
        const val ACCOUNT = "0x0000000000000000000000000000000000000001"
        const val DEPOSIT = "0x0000000000000000000000000000000000000002"
        const val HASH = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
