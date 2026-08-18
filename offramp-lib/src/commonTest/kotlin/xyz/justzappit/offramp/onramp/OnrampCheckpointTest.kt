// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OnrampCheckpointTest {
    @Test
    fun `legacy checkpoint decodes as a Base order without delivery state`() {
        val checkpoint =
            Json.decodeFromString(
                OnrampCheckpoint.serializer(),
                """{"id":"$ID","phase":"AWAITING_SETTLEMENT","orderId":"$ORDER_ID"}""",
            )

        assertEquals(OnrampDestination.BASE, checkpoint.destination)
        assertNull(checkpoint.zecDelivery)
    }

    @Test
    fun `every delivery phase accepts its minimum recovery state`() {
        OnrampZecDeliveryPhase.entries.forEach(::validCheckpoint)
    }

    @Test
    fun `checkpoint rejects unsupported versions and invalid amounts or accounts`() {
        assertFailsWith<IllegalArgumentException> {
            validCheckpoint(OnrampZecDeliveryPhase.FUNDS_ON_BASE, version = 2)
        }
        assertFailsWith<IllegalArgumentException> {
            validCheckpoint(OnrampZecDeliveryPhase.FUNDS_ON_BASE, usdcMicros = "0")
        }
        assertFailsWith<IllegalArgumentException> {
            validCheckpoint(OnrampZecDeliveryPhase.FUNDS_ON_BASE, baseAccount = "not-an-address")
        }
    }

    @Test
    fun `quote state requires route recovery handles`() {
        assertFailsWith<IllegalArgumentException> {
            validCheckpoint(OnrampZecDeliveryPhase.QUOTE_READY, zcashRecipient = null)
        }
        assertFailsWith<IllegalArgumentException> {
            validCheckpoint(OnrampZecDeliveryPhase.QUOTE_READY, depositAddress = null)
        }
        assertFailsWith<IllegalArgumentException> {
            validCheckpoint(OnrampZecDeliveryPhase.QUOTE_READY, quoteDeadlineMillis = null)
        }
    }

    @Test
    fun `submitted state requires transfer start and UserOperation hash`() {
        assertFailsWith<IllegalArgumentException> {
            validCheckpoint(OnrampZecDeliveryPhase.TRANSFER_SUBMITTED, transferStarted = false)
        }
        assertFailsWith<IllegalArgumentException> {
            validCheckpoint(OnrampZecDeliveryPhase.TRANSFER_SUBMITTED, userOperationHash = null)
        }
        assertFailsWith<IllegalArgumentException> {
            validCheckpoint(
                phase = OnrampZecDeliveryPhase.TRANSFER_SUBMITTED,
                userOperationHash = null,
                baseTransactionHash = BASE_TRANSACTION_HASH,
            )
        }
    }

    @Test
    fun `confirmed Base transaction can recover without a UserOperation hash`() {
        validCheckpoint(
            phase = OnrampZecDeliveryPhase.AWAITING_ZEC,
            userOperationHash = null,
            baseTransactionHash = BASE_TRANSACTION_HASH,
        )
    }

    @Test
    fun `post-transfer states require a confirmed Base transaction`() {
        listOf(
            OnrampZecDeliveryPhase.AWAITING_ZEC,
            OnrampZecDeliveryPhase.DELIVERED,
            OnrampZecDeliveryPhase.REFUNDED_TO_BASE,
        ).forEach { phase ->
            assertFailsWith<IllegalArgumentException> {
                validCheckpoint(phase = phase, baseTransactionHash = null)
            }
        }
    }

    @Test
    fun `ambiguous transfer start remains representable without a UserOperation hash`() {
        validCheckpoint(OnrampZecDeliveryPhase.TRANSFER_STARTING, userOperationHash = null)
        validCheckpoint(OnrampZecDeliveryPhase.NEEDS_ATTENTION, userOperationHash = null)
    }

    @Test
    fun `delivered state requires the ZEC output it must replay`() {
        assertFailsWith<IllegalArgumentException> {
            validCheckpoint(OnrampZecDeliveryPhase.DELIVERED, outputZec = null)
        }
        assertFailsWith<IllegalArgumentException> {
            validCheckpoint(OnrampZecDeliveryPhase.DELIVERED, outputZec = "")
        }
        assertEquals(OUTPUT_ZEC, validCheckpoint(OnrampZecDeliveryPhase.DELIVERED).outputZec)
    }

    @Test
    fun `refunded state requires a positive amount no larger than its input`() {
        assertFailsWith<IllegalArgumentException> {
            validCheckpoint(OnrampZecDeliveryPhase.REFUNDED_TO_BASE, refundedUsdcMicros = null)
        }
        assertFailsWith<IllegalArgumentException> {
            validCheckpoint(OnrampZecDeliveryPhase.REFUNDED_TO_BASE, refundedUsdcMicros = "0")
        }
        assertFailsWith<IllegalArgumentException> {
            validCheckpoint(OnrampZecDeliveryPhase.REFUNDED_TO_BASE, refundedUsdcMicros = "910154")
        }
    }

    @Test
    fun `Base destination rejects ZEC delivery state`() {
        assertFailsWith<IllegalArgumentException> {
            OnrampCheckpoint(
                id = ID,
                phase = OnrampPhase.COMPLETED,
                orderId = ORDER_ID,
                destination = OnrampDestination.BASE,
                zecDelivery = validCheckpoint(OnrampZecDeliveryPhase.FUNDS_ON_BASE),
            )
        }
    }

    private fun validCheckpoint(
        phase: OnrampZecDeliveryPhase,
        version: Int = 1,
        usdcMicros: String = "910153",
        baseAccount: String = BASE_ACCOUNT,
        zcashRecipient: String? = if (phase.requiresQuoteForTest) ZCASH_RECIPIENT else null,
        depositAddress: String? = if (phase.requiresQuoteForTest) DEPOSIT_ADDRESS else null,
        quoteDeadlineMillis: Long? = if (phase.requiresQuoteForTest) 1_800_000_000_000 else null,
        transferStarted: Boolean = phase.requiresTransferStartForTest,
        userOperationHash: String? = if (phase.requiresUserOperationForTest) USER_OPERATION_HASH else null,
        baseTransactionHash: String? = BASE_TRANSACTION_HASH.takeIf { phase.requiresConfirmedTransferForTest },
        outputZec: String? = if (phase == OnrampZecDeliveryPhase.DELIVERED) OUTPUT_ZEC else null,
        refundedUsdcMicros: String? =
            if (phase == OnrampZecDeliveryPhase.REFUNDED_TO_BASE) REFUNDED_USDC_MICROS else null,
    ) =
        OnrampZecDeliveryCheckpoint(
            version = version,
            phase = phase,
            usdcMicros = usdcMicros,
            baseAccount = baseAccount,
            zcashRecipient = zcashRecipient,
            depositAddress = depositAddress,
            quoteDeadlineMillis = quoteDeadlineMillis,
            transferStarted = transferStarted,
            userOperationHash = userOperationHash,
            baseTransactionHash = baseTransactionHash,
            outputZec = outputZec,
            refundedUsdcMicros = refundedUsdcMicros,
        )

    private val OnrampZecDeliveryPhase.requiresQuoteForTest: Boolean
        get() = this != OnrampZecDeliveryPhase.FUNDS_ON_BASE && this != OnrampZecDeliveryPhase.QUOTING

    private val OnrampZecDeliveryPhase.requiresTransferStartForTest: Boolean
        get() = requiresQuoteForTest && this != OnrampZecDeliveryPhase.QUOTE_READY

    private val OnrampZecDeliveryPhase.requiresUserOperationForTest: Boolean
        get() =
            this == OnrampZecDeliveryPhase.TRANSFER_SUBMITTED ||
                this == OnrampZecDeliveryPhase.AWAITING_ZEC ||
                this == OnrampZecDeliveryPhase.DELIVERED ||
                this == OnrampZecDeliveryPhase.REFUNDED_TO_BASE

    private val OnrampZecDeliveryPhase.requiresConfirmedTransferForTest: Boolean
        get() =
            this == OnrampZecDeliveryPhase.AWAITING_ZEC ||
                this == OnrampZecDeliveryPhase.DELIVERED ||
                this == OnrampZecDeliveryPhase.REFUNDED_TO_BASE

    private companion object {
        const val ID = "00000000-0000-4000-8000-000000000000"
        const val ORDER_ID = "659007"
        const val BASE_ACCOUNT = "0x0000000000000000000000000000000000000001"
        const val DEPOSIT_ADDRESS = "0x0000000000000000000000000000000000000002"
        const val ZCASH_RECIPIENT = "u1test-recipient"
        const val USER_OPERATION_HASH = "0xuser-operation"
        const val BASE_TRANSACTION_HASH = "0xbase-transaction"
        const val OUTPUT_ZEC = "0.019"
        const val REFUNDED_USDC_MICROS = "910153"
    }
}
