// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.intents

import xyz.justzappit.evm.intents.IntentTransferSigner.ExpectedTransfer
import xyz.justzappit.evm.intents.IntentTransferSigner.Rejection
import xyz.justzappit.evm.intents.IntentTransferSigner.SignResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The sell-side guard: the private-account key signs a server payload only if it moves exactly the
 * reviewed amount of the reviewed token to the quote's deposit address. Every other shape is refused.
 */
class IntentTransferSignerTest {
    private val key = IntentsAccount.derive(MNEMONIC.toCharArray())
    private val account = IntentsAccount.accountId(key)
    private val expected = ExpectedTransfer(receiverId = DEPOSIT, tokenId = NVDA, amount = AMOUNT)

    @Test
    fun `signs the reviewed transfer and nothing else`() {
        val result = IntentTransferSigner.sign(key, payload(), expected, NOW)
        val signed = assertIs<SignResult.Signed>(result)
        assertEquals(payload(), signed.payload)
        assertEquals("erc191", signed.standard)
        assertEquals(key.address, Erc191IntentSigner.recoverSigner(signed.payload, signed.signature))
    }

    @Test
    fun `accepts a deadline exactly at the allowed lifetime`() {
        val atLimit = payload(deadline = "2026-09-28T15:00:00.000Z")
        assertIs<SignResult.Signed>(IntentTransferSigner.sign(key, atLimit, expected, NOW))
    }

    @Test
    fun `refuses duplicate keys that a first-key-wins parser would read differently`() {
        val evil = transfer(receiver = "0000000000000000000000000000000000000000000000000000000000000002")
        listOf(
            // Two intents lists: this parser keeps the last (the reviewed one), another might keep the first.
            payload(extraTopLevel = "").replace("\"intents\":", "\"intents\":[$evil],\"intents\":"),
            payload(intents = "[${transfer().replace(RECEIVER_KEY, "$RECEIVER_KEY\"x\",$RECEIVER_KEY")}]"),
            payload(intents = "[${transfer(extraToken = ",\"$NVDA\":\"1\"")}]").let {
                // Same token twice: the second copy is the reviewed amount.
                it.replace("\"$NVDA\":\"$AMOUNT\",\"$NVDA\":\"1\"", "\"$NVDA\":\"1\",\"$NVDA\":\"$AMOUNT\"")
            },
        ).forEach { candidate ->
            assertEquals(
                SignResult.Refused(Rejection.NOT_CANONICAL),
                IntentTransferSigner.sign(key, candidate, expected, NOW),
                candidate,
            )
        }
    }

    @Test
    fun `refuses escapes and whitespace even when they decode to the reviewed values`() {
        listOf(
            payload(intents = "[${transfer(receiver = "\\u0030" + DEPOSIT.drop(1))}]"),
            payload().replace(",\"intents\":", ", \"intents\":"),
            payload() + "\n",
        ).forEach { candidate ->
            assertEquals(
                SignResult.Refused(Rejection.NOT_CANONICAL),
                IntentTransferSigner.sign(key, candidate, expected, NOW),
                candidate,
            )
        }
    }

    @Test
    fun `refuses payloads that are not what the user reviewed`() {
        val other = "0x9858effd232b4033e47d90003d41ec34ecaeda94"
        listOf(
            "not json" to Rejection.NOT_A_JSON_OBJECT,
            "[]" to Rejection.NOT_A_JSON_OBJECT,
            payload(extraTopLevel = ",\"memo\":\"x\"") to Rejection.UNEXPECTED_FIELDS,
            payload().replace(",\"nonce\":\"$NONCE\"", "") to Rejection.UNEXPECTED_FIELDS,
            payload(signer = other) to Rejection.WRONG_SIGNER,
            // NEAR account IDs are lowercase; a checksummed one names an account the contract refuses.
            payload(signer = key.address.checksumHex) to Rejection.WRONG_SIGNER,
            payload(contract = "evil.near") to Rejection.WRONG_VERIFYING_CONTRACT,
            payload(deadline = "tomorrow") to Rejection.UNREADABLE_DEADLINE,
            payload(deadline = "2026-09-28T14:00:00.000Z") to Rejection.EXPIRED,
            payload(deadline = "2026-09-28T15:00:00.001Z") to Rejection.DEADLINE_TOO_FAR,
            payload(nonce = "AAAA") to Rejection.UNREADABLE_NONCE,
            payload(nonce = "not base64!") to Rejection.UNREADABLE_NONCE,
            payload(nonce = NONCE.trimEnd('=')) to Rejection.UNREADABLE_NONCE,
            payload(intents = "[]") to Rejection.UNEXPECTED_INTENTS,
            payload(intents = "[${transfer()},${transfer()}]") to Rejection.UNEXPECTED_INTENTS,
            payload(intents = "[${transfer(amount = "441000000000000001")}]") to Rejection.UNEXPECTED_INTENTS,
            payload(intents = "[${transfer(receiver = other)}]") to Rejection.UNEXPECTED_INTENTS,
            payload(intents = "[${transfer(token = "nep141:wrap.near")}]") to Rejection.UNEXPECTED_INTENTS,
            payload(intents = "[${transfer(extraToken = EXTRA_TOKEN)}]") to Rejection.UNEXPECTED_INTENTS,
            payload(intents = "[${transfer(extraField = ",\"memo\":\"x\"")}]") to Rejection.UNEXPECTED_INTENTS,
            payload(intents = "[${transfer(kind = "ft_withdraw")}]") to Rejection.UNEXPECTED_INTENTS,
            payload(intents = "[${transfer(amountJson = "441000000000000000")}]") to Rejection.UNEXPECTED_INTENTS,
        ).forEach { (candidate, reason) ->
            assertEquals(
                SignResult.Refused(reason),
                IntentTransferSigner.sign(key, candidate, expected, NOW),
                candidate,
            )
        }
    }

    @Test
    fun `a longer allowed lifetime is the caller's explicit choice`() {
        val far = payload(deadline = "2026-09-28T15:00:00.001Z")
        assertIs<SignResult.Signed>(
            IntentTransferSigner.sign(
                key = key,
                payload = far,
                expected = expected,
                nowMillis = NOW,
                maxTtlMillis = 2 * IntentTransferSigner.DEFAULT_MAX_TTL_MILLIS,
            ),
        )
    }

    private fun transfer(
        kind: String = "transfer",
        receiver: String = DEPOSIT,
        token: String = NVDA,
        amount: String = AMOUNT,
        amountJson: String = "\"$amount\"",
        extraToken: String = "",
        extraField: String = "",
    ) = "{\"intent\":\"$kind\",\"receiver_id\":\"$receiver\",\"tokens\":{\"$token\":$amountJson$extraToken}$extraField}"

    private fun payload(
        signer: String = account,
        contract: String = "intents.near",
        deadline: String = "2026-09-28T14:30:00.000Z",
        nonce: String = NONCE,
        intents: String = "[${transfer()}]",
        extraTopLevel: String = "",
    ) = "{\"signer_id\":\"$signer\",\"verifying_contract\":\"$contract\",\"deadline\":\"$deadline\"," +
        "\"nonce\":\"$nonce\",\"intents\":$intents$extraTopLevel}"

    private companion object {
        const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        const val DEPOSIT = "0000000000000000000000000000000000000000000000000000000000000001"
        const val NVDA = "nep141:bnb-0xa9ee28c80f960b889dfbd1902055218cba016f75.omdep.near"
        const val AMOUNT = "441000000000000000"
        const val NONCE = "Vij2xgAlKBKzAMg6wgOB2RgAwGTZ2YDZGAECAwQFBgc="
        const val NOW = 1_790_604_000_000L // 2026-09-28T14:00:00.000Z
        const val RECEIVER_KEY = "\"receiver_id\":"
        const val EXTRA_TOKEN = ",\"nep141:wrap.near\":\"1\""
    }
}
