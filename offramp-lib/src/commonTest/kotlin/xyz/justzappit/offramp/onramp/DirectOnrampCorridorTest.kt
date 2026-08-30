// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import xyz.justzappit.evm.abi.AbiEncoder
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DirectOnrampCorridorTest {
    private val orderId: BigInteger = bigIntegerValueOf(7)
    private val fiat = Usdc6.ofMicros(445_000_000)

    @Test
    fun `every corridor's bytes32 word round-trips back to its own code`() {
        CurrencyCode.entries.forEach { currency ->
            val word = "0x" + AbiEncoder.bytes32String(currency.code).value.toHex()
            assertEquals(currency, corridorFromBytes32(word), "round trip failed for ${currency.code}")
        }
    }

    @Test
    fun `the INR word is the one the chain really stores`() {
        assertEquals(
            CurrencyCode.Inr,
            corridorFromBytes32("0x494e520000000000000000000000000000000000000000000000000000000000"),
        )
    }

    @Test
    fun `an undecodable corridor is paid verbatim, never as a UPI intent`() {
        val payload = "unknown payment destination"
        val instruction = paymentInstructionFor(payload, orderId, fiat, currency = null)

        val plain = assertIs<OnrampPaymentInstruction.Plain>(instruction)
        assertEquals(payload, plain.address)
    }

    @Test
    fun `INR builds a UPI instruction`() {
        assertIs<OnrampPaymentInstruction.Upi>(
            paymentInstructionFor("merchant@upi", orderId, fiat, CurrencyCode.Inr),
        )
    }

    @Test
    fun `catalog fields match the official client`() {
        assertFields(CurrencyCode.Brl, "alice@example.com", OnrampPaymentFieldKind.PIX_KEY to "alice@example.com")
        assertFields(CurrencyCode.Idr, "08123456789", OnrampPaymentFieldKind.PHONE_NUMBER to "08123456789")
        assertFields(CurrencyCode.Ars, "ALIAS.PAGO", OnrampPaymentFieldKind.PAYMENT_ALIAS to "ALIAS.PAGO")
        assertFields(CurrencyCode.Cop, "3001234567", OnrampPaymentFieldKind.PAYMENT_ALIAS to "3001234567")
        assertFields(
            CurrencyCode.Ven,
            "04121234567|V12345678|Banesco",
            OnrampPaymentFieldKind.PHONE_NUMBER to "04121234567",
            OnrampPaymentFieldKind.DOCUMENT_ID to "V12345678",
            OnrampPaymentFieldKind.BANK to "Banesco",
        )
        assertFields(
            CurrencyCode.Ngn,
            "0123456789|Access Bank|Ada Okafor",
            OnrampPaymentFieldKind.ACCOUNT_NUMBER to "0123456789",
            OnrampPaymentFieldKind.BANK_NAME to "Access Bank",
            OnrampPaymentFieldKind.ACCOUNT_NAME to "Ada Okafor",
        )
        assertFields(CurrencyCode.Bob, "123456789", OnrampPaymentFieldKind.ACCOUNT_NUMBER to "123456789")
        assertFields(
            CurrencyCode.Cup,
            "51234567|9225123456789012",
            OnrampPaymentFieldKind.PHONE_NUMBER to "51234567",
            OnrampPaymentFieldKind.CARD_NUMBER to "9225123456789012",
        )
        assertFields(
            CurrencyCode.Ecu,
            "Pichincha|Savings|1234567890|Ana Perez|0102030405",
            OnrampPaymentFieldKind.BANK_NAME to "Pichincha",
            OnrampPaymentFieldKind.ACCOUNT_TYPE to "Savings",
            OnrampPaymentFieldKind.ACCOUNT_NUMBER to "1234567890",
            OnrampPaymentFieldKind.ACCOUNT_NAME to "Ana Perez",
            OnrampPaymentFieldKind.CEDULA to "0102030405",
        )
        assertFields(
            CurrencyCode.Pen,
            "+51 912345678|12345678901234567890",
            OnrampPaymentFieldKind.PHONE_NUMBER to "912345678",
            OnrampPaymentFieldKind.CCI to "12345678901234567890",
        )
        assertFields(
            CurrencyCode.Php,
            "09171234567|BDO",
            OnrampPaymentFieldKind.PHONE_NUMBER to "09171234567",
            OnrampPaymentFieldKind.BANK_NAME to "BDO",
        )
    }

    @Test
    fun `pay QR corridors render the official payload`() {
        val payloads =
            mapOf(
                CurrencyCode.Ven to "QUJDRA==?merchantId=123",
                CurrencyCode.Pen to "00020153036045802PE",
                CurrencyCode.Php to "00020153036085802PH",
                CurrencyCode.Bob to "00020153030685802BO",
            )

        payloads.forEach { (currency, payload) ->
            assertEquals(
                payload,
                assertIs<OnrampPaymentInstruction.Qr>(
                    paymentInstructionFor(payload, orderId, fiat, currency),
                ).payload,
            )
        }
    }

    @Test
    fun `packed QR and fallback fields are both retained`() {
        val qr = "QUJDRA==?merchantId=123"
        val instruction =
            assertIs<OnrampPaymentInstruction.Fields>(
                paymentInstructionFor(
                    "$qr||04121234567|V12345678|Banesco",
                    orderId,
                    fiat,
                    CurrencyCode.Ven,
                ),
            )

        assertEquals(qr, instruction.qrPayload)
        assertEquals(
            listOf(
                OnrampPaymentFieldKind.PHONE_NUMBER,
                OnrampPaymentFieldKind.DOCUMENT_ID,
                OnrampPaymentFieldKind.BANK,
            ),
            instruction.fields.map { it.kind },
        )
    }

    @Test
    fun `corridor screening metadata matches the official client`() {
        val expected =
            mapOf(
                CurrencyCode.Inr to DirectOnrampMetadata("India", "UPI"),
                CurrencyCode.Brl to DirectOnrampMetadata("Brazil", "PIX"),
                CurrencyCode.Idr to DirectOnrampMetadata("Indonesia", "QRIS"),
                CurrencyCode.Ars to DirectOnrampMetadata("Argentina", "ALIAS"),
                CurrencyCode.Ven to DirectOnrampMetadata("Venezuela", "PAGO_MOVIL"),
                CurrencyCode.Ngn to DirectOnrampMetadata("Nigeria", "NIP"),
                CurrencyCode.Cop to DirectOnrampMetadata("Colombia", "TRANSFERENCIA"),
                CurrencyCode.Bob to DirectOnrampMetadata("Bolivia", "QR_SIMPLE"),
                CurrencyCode.Cup to DirectOnrampMetadata("Cuba", "TRANSFERMOVIL"),
                CurrencyCode.Ecu to DirectOnrampMetadata("Ecuador", "TRANSFERENCIA"),
                CurrencyCode.Pen to DirectOnrampMetadata("Peru", "YAPE_PLIN_CCI"),
                CurrencyCode.Php to DirectOnrampMetadata("Philippines", "INSTAPAY"),
            )

        assertEquals(expected, CurrencyCode.entries.associateWith { it.directOnrampMetadata })
    }

    @Test
    fun `processing time seconds use the frontend minute format`() {
        assertEquals("1", 60L.toMinutes())
        assertEquals("1.5", 90L.toMinutes())
        assertEquals("1.01", 61L.toMinutes())
    }

    @Test
    fun `a corridor this app does not serve decodes to nothing, not to a default`() {
        val word = "0x" + AbiEncoder.bytes32String("MEX").value.toHex()
        assertNull(corridorFromBytes32(word))
    }

    private fun assertFields(
        currency: CurrencyCode,
        paymentId: String,
        vararg expected: Pair<OnrampPaymentFieldKind, String>,
    ) {
        val instruction =
            assertIs<OnrampPaymentInstruction.Fields>(
                paymentInstructionFor(paymentId, orderId, fiat, currency),
            )
        val fields = instruction.fields
        assertEquals(expected.map { it.first }, fields.map { it.kind })
        assertEquals(expected.map { it.second }, fields.map { it.value })
        assertEquals(expected.joinToString("|") { it.second }, instruction.copyValue)
    }
}
