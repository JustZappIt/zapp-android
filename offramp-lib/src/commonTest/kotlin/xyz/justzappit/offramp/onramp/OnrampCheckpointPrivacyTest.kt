// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import kotlinx.serialization.json.Json
import xyz.justzappit.offramp.p2p.CurrencyCode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnrampCheckpointPrivacyTest {
    @Test
    fun `checkpoint serialization cannot contain decrypted payment material`() {
        val encoded =
            Json.encodeToString(
                OnrampCheckpoint.serializer(),
                OnrampCheckpoint(
                    id = "00000000-0000-4000-8000-000000000000",
                    phase = OnrampPhase.AWAITING_PAYMENT,
                    orderId = "659007",
                ),
            )

        listOf("paymentAddress", "merchantUpi", "payUri", "intentUrl", "payeeName", "vpa", "upi://", "0x")
            .forEach { forbidden -> assertFalse(encoded.contains(forbidden, ignoreCase = true)) }
    }

    @Test
    fun `payment instructions are redacted in toString`() {
        val instructions =
            listOf(
                OnrampPaymentInstruction.Upi("merchant@upi", "upi://pay?pa=merchant@upi", "100.00"),
                OnrampPaymentInstruction.Qr("upi://pay?pa=merchant@upi"),
                OnrampPaymentInstruction.Fields(listOf(OnrampPaymentInstruction.Field("VPA", "merchant@upi"))),
                OnrampPaymentInstruction.Plain("merchant@upi"),
            )

        instructions.forEach { instruction ->
            assertFalse(instruction.toString().contains("merchant@upi"))
            assertFalse(instruction.toString().contains("upi://"))
        }
    }

    @Test
    fun `delivery serialization contains recovery handles but no wallet or provider secrets`() {
        val checkpoint =
            OnrampCheckpoint(
                id = "00000000-0000-4000-8000-000000000000",
                phase = OnrampPhase.COMPLETED,
                orderId = "659007",
                destination = OnrampDestination.ZCASH,
                zecDelivery =
                    OnrampZecDeliveryCheckpoint(
                        phase = OnrampZecDeliveryPhase.QUOTE_READY,
                        usdcMicros = "910153",
                        baseAccount = BASE_ACCOUNT,
                        zcashRecipient = ZCASH_RECIPIENT,
                        depositAddress = DEPOSIT_ADDRESS,
                        quoteDeadlineMillis = 1_800_000_000_000,
                    ),
            )
        val encoded = Json.encodeToString(OnrampCheckpoint.serializer(), checkpoint)

        listOf(BASE_ACCOUNT, ZCASH_RECIPIENT, DEPOSIT_ADDRESS).forEach { assertTrue(encoded.contains(it)) }
        listOf("merchant@upi", "seedPhrase", "privateKey", "viewingKey", "rawQuote", "requestBody", "responseBody")
            .forEach { forbidden -> assertFalse(encoded.contains(forbidden, ignoreCase = true)) }
        listOf(BASE_ACCOUNT, ZCASH_RECIPIENT, DEPOSIT_ADDRESS)
            .forEach { sensitive -> assertFalse(checkpoint.toString().contains(sensitive)) }
    }

    @Test
    fun `a settled delivery keeps its amounts out of toString`() {
        val delivered =
            OnrampZecDeliveryCheckpoint(
                phase = OnrampZecDeliveryPhase.DELIVERED,
                usdcMicros = "910153",
                baseAccount = BASE_ACCOUNT,
                zcashRecipient = ZCASH_RECIPIENT,
                depositAddress = DEPOSIT_ADDRESS,
                quoteDeadlineMillis = 1_800_000_000_000,
                transferStarted = true,
                baseTransactionHash = "0xbase-transaction",
                outputZec = "0.019",
            )

        listOf("910153", "0.019", BASE_ACCOUNT, ZCASH_RECIPIENT, DEPOSIT_ADDRESS, "0xbase-transaction")
            .forEach { sensitive -> assertFalse(delivered.toString().contains(sensitive)) }
    }

    private companion object {
        const val BASE_ACCOUNT = "0x0000000000000000000000000000000000000001"
        const val DEPOSIT_ADDRESS = "0x0000000000000000000000000000000000000002"
        const val ZCASH_RECIPIENT = "u1test-recipient"
    }
}
