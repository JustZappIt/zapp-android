// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import kotlinx.serialization.json.Json
import xyz.justzappit.offramp.p2p.CurrencyCode
import kotlin.test.Test
import kotlin.test.assertFalse

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
}
