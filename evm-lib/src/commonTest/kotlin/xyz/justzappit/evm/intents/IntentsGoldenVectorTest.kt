// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.intents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import xyz.justzappit.evm.hd.EvmKeyDerivation
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Vectors produced by the official NEAR Intents SDK, `@defuse-protocol/intents-sdk` 0.88.0 with
 * `@defuse-protocol/internal-utils` 0.41.0 and viem 2.56.9, from the public BIP-39 test mnemonic:
 * `mnemonicToAccount(m, { accountIndex: 7 })`, `VersionedNonceBuilder.encodeNonce`, and
 * `createIntentSignerViem(...).signIntent`. Any byte of difference means the server would reject us.
 */
class IntentsGoldenVectorTest {
    private val key = IntentsAccount.derive(MNEMONIC.toCharArray())

    @Test
    fun `private account derives the SDK's m 44 60 7 0 0 address`() {
        assertEquals("m/44'/60'/7'/0/0", IntentsAccount.DERIVATION_PATH)
        assertEquals(Address.parse(ACCOUNT_7), key.address)
        assertEquals(ACCOUNT_7, IntentsAccount.accountId(key))
    }

    @Test
    fun `nonce matches VersionedNonceBuilder byte for byte`() {
        AUTH_VECTOR.let { v ->
            val nonce = IntentNonce.encode(SALT.hexToBytes(), DEADLINE_MS, START_MS, v.random7.hexToBytes())
            assertEquals(v.nonceHex, nonce.toHex())
            assertEquals(v.nonceBase64, Base64.encode(nonce))
        }
        TRANSFER_VECTOR.let { v ->
            assertEquals(
                v.nonceBase64,
                IntentNonce.encodeBase64(SALT.hexToBytes(), DEADLINE_MS, START_MS, v.random7.hexToBytes()),
            )
        }
    }

    @Test
    fun `login payload is the SDK's exact string`() {
        val payload = IntentPayload.build(IntentsAccount.accountId(key), DEADLINE_MS, AUTH_VECTOR.nonceBase64)
        assertEquals(AUTH_VECTOR.payload, payload)
    }

    @Test
    fun `payload with intents is the SDK's exact string`() {
        val intents = Json.parseToJsonElement(TRANSFER_INTENTS) as JsonArray
        val payload =
            IntentPayload.build(IntentsAccount.accountId(key), DEADLINE_MS, TRANSFER_VECTOR.nonceBase64, intents)
        assertEquals(TRANSFER_VECTOR.payload, payload)
    }

    @Test
    fun `signatures match transformERC191Signature output`() {
        assertEquals(AUTH_VECTOR.signature, Erc191IntentSigner.sign(key, AUTH_VECTOR.payload))
        assertEquals(TRANSFER_VECTOR.signature, Erc191IntentSigner.sign(key, TRANSFER_VECTOR.payload))
        assertEquals(RAW_SIGNATURE, Erc191IntentSigner.sign(key, RAW_PAYLOAD))
    }

    @Test
    fun `signatures recover to the private account`() {
        listOf(
            AUTH_VECTOR.payload to AUTH_VECTOR.signature,
            TRANSFER_VECTOR.payload to TRANSFER_VECTOR.signature,
            RAW_PAYLOAD to RAW_SIGNATURE,
        ).forEach { (payload, signature) ->
            assertEquals(key.address, Erc191IntentSigner.recoverSigner(payload, signature))
        }
    }

    @Test
    fun `IntentsLogin produces the SDK's login from one server clock reading`() {
        val signed =
            IntentsLogin.sign(
                key = key,
                salt = IntentNonce.parseSalt(SALT),
                serverNowMillis = START_MS,
                ttlMillis = DEADLINE_MS - START_MS,
                random = AUTH_VECTOR.random7.hexToBytes(),
            )
        assertEquals(AUTH_VECTOR.payload, signed.payload)
        assertEquals(AUTH_VECTOR.signature, signed.signature)
        assertEquals("erc191", signed.standard)
    }

    @Test
    fun `derivation matches viem mnemonicToAccount for other accounts and passphrases`() {
        // viem 2.56.9 mnemonicToAccount(m, { accountIndex, addressIndex, passphrase }).
        listOf(
            Triple(0, 0, "") to "0x9858effd232b4033e47d90003d41ec34ecaeda94",
            Triple(1, 0, "") to "0x78839f6054d7ed13918bae0473ba31b1ca9d7265",
            Triple(7, 0, "") to "0x8bae832c68d7c90c11bca5eb8bd0f14d49bd9e8f",
            Triple(7, 3, "") to "0xcd1fc1e1ed8a490c0033ab468fba9bd31d1e2e52",
            Triple(2_147_483_647, 0, "") to "0x12dcc9f4feea216da086a8afafcdd4438bf7e751",
            Triple(7, 0, "TREZOR") to "0xdc8626e12b05a73724b4fa1fd526b68196dab807",
        ).forEach { (path, expected) ->
            val (account, index, passphrase) = path
            val derived = EvmKeyDerivation.deriveAccount(MNEMONIC.toCharArray(), account, index, passphrase)
            assertEquals(expected, derived.address.lowercaseHex, "m/44'/60'/$account'/0/$index '$passphrase'")
        }
    }

    @Test
    fun `personal_sign matches viem including a leading zero r byte`() {
        // viem signMessage + transformERC191Signature. payload-37's r starts with 0x00, so its Base58 starts
        // with '1'; payload-23 encodes to a shorter string.
        listOf(
            "payload-0" to
                "secp256k1:7E9g5JfecXUKCpzXXMcnyGUGzmxr8thv6k7ZwATvqE3hQx4fWyUhQnRzqNW9" +
                "SipJjdzs354DNSDLinjNU8bjpQj2Q",
            "payload-1" to
                "secp256k1:NiYqZrHfLbMfrM4jvv3AqrZupJPxdjdNNjLeh96KaACc9turNN9BrnnEdDXs" +
                "o6VDhU5psZn1EmZzgQpZCd4BKDDFe",
            "payload-2" to
                "secp256k1:4tKKsKhwxs87b5ANaEPyPHcRcSnP6XEZEfUcmprf9UHXpGLtzZWEUUaQRABv" +
                "Wm5PSvP6JMtjAhxutG65SBAuLpJZM",
            "payload-23" to
                "secp256k1:9r89yrR2zKoxAgJ72q3UrBQtYUwnpWMDsNEvT8nVereE2tZQAYtpoUzz1AMu" +
                "WRLUYXkwM4go1FzsMrhPy2R7G2TP5",
            "payload-37" to
                "secp256k1:1QD9sgRfiwuoHCTDVXrAnUb1RqRD4pgpP6r8tea8y73yVqqLQvwyQJw9EqUP" +
                "PunkrjjhUJGbgeAXo2V6pRh9vgFH",
        ).forEach { (payload, expected) ->
            assertEquals(expected, Erc191IntentSigner.sign(key, payload), payload)
            assertEquals(key.address, Erc191IntentSigner.recoverSigner(payload, expected), payload)
        }
    }

    @Test
    fun `iso deadline matches Date toISOString`() {
        assertEquals("2026-09-28T14:03:00.000Z", IntentPayload.isoMillis(DEADLINE_MS))
        assertEquals("1970-01-01T00:00:00.000Z", IntentPayload.isoMillis(0))
        assertEquals("9999-12-31T23:59:59.999Z", IntentPayload.isoMillis(253_402_300_799_999))
        assertEquals("2024-02-29T23:59:59.999Z", IntentPayload.isoMillis(1_709_251_199_999))
        assertEquals("2000-03-01T00:00:00.001Z", IntentPayload.isoMillis(951_868_800_001))
    }

    private data class Vector(
        val random7: String,
        val nonceHex: String,
        val nonceBase64: String,
        val payload: String,
        val signature: String,
    )

    private companion object {
        const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        const val ACCOUNT_7 = "0x8bae832c68d7c90c11bca5eb8bd0f14d49bd9e8f"
        const val SALT = "252812b3"
        const val DEADLINE_MS = 1_790_604_180_000L // 2026-09-28T14:03:00.000Z
        const val START_MS = 1_790_604_000_000L // 2026-09-28T14:00:00.000Z

        val AUTH_VECTOR =
            Vector(
                random7 = "01020304050607",
                nonceHex = "5628f6c600252812b300c83ac20381d91800c064d9d980d91801020304050607",
                nonceBase64 = "Vij2xgAlKBKzAMg6wgOB2RgAwGTZ2YDZGAECAwQFBgc=",
                payload =
                    "{\"signer_id\":\"0x8bae832c68d7c90c11bca5eb8bd0f14d49bd9e8f\"," +
                        "\"verifying_contract\":\"intents.near\"," +
                        "\"deadline\":\"2026-09-28T14:03:00.000Z\"," +
                        "\"nonce\":\"Vij2xgAlKBKzAMg6wgOB2RgAwGTZ2YDZGAECAwQFBgc=\"," +
                        "\"intents\":[]}",
                signature =
                    "secp256k1:Pb264Di8CfPrQb1TE93X6tkSkPDVQ3SEX7TjSjtYP5s2HYNG2FT9dvu331T6X" +
                        "NabcL7yq1afHvjEghcmJgfUtEG4X",
            )

        const val TRANSFER_INTENTS =
            "[{\"intent\":\"transfer\"," +
                "\"receiver_id\":\"0000000000000000000000000000000000000000000000000000000000000001\"," +
                "\"tokens\":{\"nep141:bnb-0xa9ee28c80f960b889dfbd1902055218cba016f75.omdep.near\":" +
                "\"441000000000000000\"}}]"

        val TRANSFER_VECTOR =
            Vector(
                random7 = "ffeeddccbbaa99",
                nonceHex = "5628f6c600252812b300c83ac20381d91800c064d9d980d918ffeeddccbbaa99",
                nonceBase64 = "Vij2xgAlKBKzAMg6wgOB2RgAwGTZ2YDZGP/u3cy7qpk=",
                payload =
                    "{\"signer_id\":\"0x8bae832c68d7c90c11bca5eb8bd0f14d49bd9e8f\"," +
                        "\"verifying_contract\":\"intents.near\"," +
                        "\"deadline\":\"2026-09-28T14:03:00.000Z\"," +
                        "\"nonce\":\"Vij2xgAlKBKzAMg6wgOB2RgAwGTZ2YDZGP/u3cy7qpk=\"," +
                        "\"intents\":$TRANSFER_INTENTS}",
                signature =
                    "secp256k1:Nwvyzgz6i4fFMswKHnMat3h473ccmwzqGUnpEdyRgPSe3bPSL4fDTMqnPvgUiuVyF9vJg2B" +
                        "QZS9udriyN7R1Q9e5u",
            )

        const val RAW_PAYLOAD = "Zapp ✓ intents — raw payload"
        const val RAW_SIGNATURE =
            "secp256k1:85NoRjCroUdbgmwdpsCTLjtBMd4BCoqExi9SADQXX49MH5D2EX24XDMrwdqKLttf9LZ9Eo" +
                "5qqG4Vk7pcdyNTbe69N"
    }
}
