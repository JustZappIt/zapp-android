// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift.model

import cash.z.ecc.android.sdk.model.ZcashNetwork
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class GiftLinkCodecTest {
    @Test
    fun `round trips a payload through a link`() {
        val payload = payload()

        val decoded = GiftLinkCodec.decode(GiftLinkCodec.encode(payload), ZcashNetwork.Mainnet)

        assertEquals(payload, decoded)
    }

    @Test
    fun `round trips optional fields both present and absent`() {
        val bare = payload(message = null, expiresAt = null)
        val full = payload(message = "happy birthday", expiresAt = "2026-12-24T00:00:00Z")

        assertEquals(bare, GiftLinkCodec.decode(GiftLinkCodec.encode(bare), ZcashNetwork.Mainnet))
        assertEquals(full, GiftLinkCodec.decode(GiftLinkCodec.encode(full), ZcashNetwork.Mainnet))
    }

    @Test
    fun `encodes to the shared link shape`() {
        val link = GiftLinkCodec.encode(payload())

        assertTrue(link.startsWith("https://$GIFT_LINK_HOST/c/v1#k="))
        // The secret must sit in the fragment, where no HTTP client ever puts it on the wire.
        assertFalse(link.substringBefore('#').contains("k="))
    }

    @Test
    fun `encodes base64url unpadded across every remainder length`() {
        // Walking the message length by one walks the payload length by one, so this covers inputs
        // whose byte count leaves each of the three possible remainders mod 3.
        (0..3).forEach { extra ->
            val payload = payload(message = "m".repeat(extra))
            val body = GiftLinkCodec.encode(payload).substringAfter("#k=")

            assertFalse(body.contains('='), "padding leaked for message length $extra")
            assertFalse(body.contains('+') || body.contains('/'), "non-url-safe alphabet for length $extra")
            assertEquals(payload, GiftLinkCodec.decode(GiftLinkCodec.encode(payload), ZcashNetwork.Mainnet))
        }
    }

    @Test
    fun `accepts a padded fragment on decode`() {
        val payload = payload()
        val json = jsonOf(payload)
        val padded = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT).encode(json.toByteArray())

        // Only meaningful if this input actually needed padding.
        assertTrue(padded.endsWith("="))
        assertEquals(payload, GiftLinkCodec.decode("https://$GIFT_LINK_HOST/c/v1#k=$padded", ZcashNetwork.Mainnet))
    }

    @Test
    fun `rejects a URI over the size bound by character count`() {
        val link = "https://$GIFT_LINK_HOST/c/v1#k=" + "A".repeat(GiftLinkCodec.MAX_URI_BYTES + 1)

        assertEquals(GiftLinkError.TOO_LARGE, errorFrom(link))
    }

    @Test
    fun `rejects a URI over the size bound by UTF-8 bytes alone`() {
        // Under the bound by String.length, over it by byte size: length counts UTF-16 units, so
        // checking only length would let this through.
        val body = "é".repeat(GiftLinkCodec.MAX_URI_BYTES - 1000)
        val link = "https://$GIFT_LINK_HOST/c/v1#k=$body"

        assertTrue(link.length <= GiftLinkCodec.MAX_URI_BYTES)
        assertTrue(link.toByteArray().size > GiftLinkCodec.MAX_URI_BYTES)
        assertEquals(GiftLinkError.TOO_LARGE, errorFrom(link))
    }

    @Test
    fun `rejects links that are not ours`() {
        val body = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(jsonOf(payload()).toByteArray())

        assertEquals(GiftLinkError.MALFORMED_URI, errorFrom("http://$GIFT_LINK_HOST/c/v1#k=$body"))
        assertEquals(GiftLinkError.MALFORMED_URI, errorFrom("https://evil.example.com/c/v1#k=$body"))
        assertEquals(GiftLinkError.MALFORMED_URI, errorFrom("https://$GIFT_LINK_HOST/c/v2#k=$body"))
        assertEquals(GiftLinkError.MALFORMED_URI, errorFrom("https://$GIFT_LINK_HOST/c/v1"))
        assertEquals(GiftLinkError.MALFORMED_URI, errorFrom("https://$GIFT_LINK_HOST/c/v1#$body"))
        assertEquals(GiftLinkError.MALFORMED_URI, errorFrom("not a uri at all"))
    }

    @Test
    fun `rejects a link carrying the payload in the query`() {
        val body = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(jsonOf(payload()).toByteArray())

        assertEquals(GiftLinkError.MALFORMED_URI, errorFrom("https://$GIFT_LINK_HOST/c/v1?k=$body#k=$body"))
    }

    @Test
    fun `matches the host case-insensitively`() {
        val link = GiftLinkCodec.encode(payload()).replace(GIFT_LINK_HOST, GIFT_LINK_HOST.uppercase())

        assertEquals(payload(), GiftLinkCodec.decode(link, ZcashNetwork.Mainnet))
    }

    @Test
    fun `rejects a fragment that is not base64`() {
        assertEquals(GiftLinkError.MALFORMED_PAYLOAD, errorFrom("https://$GIFT_LINK_HOST/c/v1#k=not*base64"))
    }

    @Test
    fun `rejects an unknown field as a newer format rather than a broken link`() {
        val json = jsonOf(payload()).dropLast(1) + ""","surprise":"tracking-id"}"""

        // Still refused — an unrecognised field could change who may claim, or for how much — but
        // told apart from gibberish. The card is real and there is no reclaim, so the recipient
        // has to be told to update rather than told their gift is broken (§2.1).
        assertEquals(GiftLinkError.NEWER_FORMAT, errorFrom(linkOf(json)))
    }

    @Test
    fun `reports an unknown version even when it also carries unknown fields`() {
        val json = jsonOf(payload(v = 2)).dropLast(1) + ""","surprise":"tracking-id"}"""

        // The version is the more specific answer, so it wins the ordering.
        assertEquals(GiftLinkError.UNSUPPORTED_VERSION, errorFrom(linkOf(json)))
    }

    @Test
    fun `every field this build encodes is one it recognises on the way back in`() {
        // The unknown-field check is a hand-maintained key set, so a field added to the payload and
        // not to it would make this build refuse its own links — on money with no reclaim.
        val everyField = payload(expiresAt = "2027-01-01T00:00:00Z", message = "hi")

        assertEquals(everyField, GiftLinkCodec.decode(GiftLinkCodec.encode(everyField), ZcashNetwork.Mainnet))
    }

    @Test
    fun `rejects a missing required field`() {
        val json = jsonOf(payload()).replace(""""address":"$ADDRESS",""", "")

        assertEquals(GiftLinkError.MALFORMED_PAYLOAD, errorFrom(linkOf(json)))
    }

    @Test
    fun `rejects an unknown version`() {
        assertEquals(GiftLinkError.UNSUPPORTED_VERSION, errorFrom(linkOf(jsonOf(payload(v = 2)))))
        assertEquals(GiftLinkError.UNSUPPORTED_VERSION, errorFrom(linkOf(jsonOf(payload(v = 0)))))
    }

    @Test
    fun `rejects a card minted for the other network`() {
        val mainnetLink = GiftLinkCodec.encode(payload())

        assertEquals(GiftLinkError.NETWORK_MISMATCH, errorFrom(mainnetLink, ZcashNetwork.Testnet))
    }

    @Test
    fun `rejects an unrecognised network name`() {
        assertEquals(GiftLinkError.NETWORK_MISMATCH, errorFrom(linkOf(jsonOf(payload(network = "regtest")))))
        assertEquals(GiftLinkError.NETWORK_MISMATCH, errorFrom(linkOf(jsonOf(payload(network = "")))))
    }

    @Test
    fun `rejects an empty address`() {
        assertEquals(GiftLinkError.INVALID_ADDRESS, errorFrom(linkOf(jsonOf(payload(address = "   ")))))
    }

    @Test
    fun `rejects a non-positive or unparseable amount`() {
        assertEquals(GiftLinkError.INVALID_AMOUNT, errorFrom(linkOf(jsonOf(payload(amount = "0")))))
        assertEquals(GiftLinkError.INVALID_AMOUNT, errorFrom(linkOf(jsonOf(payload(amount = "-1")))))
        assertEquals(GiftLinkError.INVALID_AMOUNT, errorFrom(linkOf(jsonOf(payload(amount = "1.5")))))
        assertEquals(GiftLinkError.INVALID_AMOUNT, errorFrom(linkOf(jsonOf(payload(amount = "not a number")))))
        // Beyond the money supply, and beyond what Zatoshi will construct.
        assertEquals(GiftLinkError.INVALID_AMOUNT, errorFrom(linkOf(jsonOf(payload(amount = "2100000000000001")))))
    }

    @Test
    fun `rejects a mnemonic that is not a valid 24 word phrase`() {
        val twelve = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

        assertEquals(GiftLinkError.INVALID_MNEMONIC, errorFrom(linkOf(jsonOf(payload(mnemonic = twelve)))))
        // 24 words, wrong checksum word.
        assertEquals(
            GiftLinkError.INVALID_MNEMONIC,
            errorFrom(linkOf(jsonOf(payload(mnemonic = MNEMONIC.replace("art", "abandon")))))
        )
        assertEquals(
            GiftLinkError.INVALID_MNEMONIC,
            errorFrom(linkOf(jsonOf(payload(mnemonic = MNEMONIC.replace("art", "zzzz")))))
        )
    }

    @Test
    fun `normalises whitespace in a mnemonic that survived a copy-paste`() {
        val mangled = "  " + MNEMONIC.replace(" ", "  ").replace("art", "\nart") + "  "

        val decoded = GiftLinkCodec.decode(linkOf(jsonOf(payload(mnemonic = mangled))), ZcashNetwork.Mainnet)

        assertEquals(MNEMONIC, decoded.mnemonic)
    }

    @Test
    fun `rejects a birthday below sapling activation or at zero`() {
        val belowSapling = ZcashNetwork.Mainnet.saplingActivationHeight.value - 1

        assertEquals(GiftLinkError.INVALID_BIRTHDAY, errorFrom(linkOf(jsonOf(payload(birthday = belowSapling)))))
        assertEquals(GiftLinkError.INVALID_BIRTHDAY, errorFrom(linkOf(jsonOf(payload(birthday = 0)))))
        assertEquals(GiftLinkError.INVALID_BIRTHDAY, errorFrom(linkOf(jsonOf(payload(birthday = -1)))))
    }

    @Test
    fun `applies the sapling floor of the network named in the payload`() {
        // Testnet activates far below mainnet, so a height legal on testnet is illegal on mainnet.
        val testnetOnly = ZcashNetwork.Testnet.saplingActivationHeight.value + 1
        val json = jsonOf(payload(network = "test", birthday = testnetOnly))

        assertEquals(testnetOnly, GiftLinkCodec.decode(linkOf(json), ZcashNetwork.Testnet).birthdayHeight)
        assertEquals(
            GiftLinkError.INVALID_BIRTHDAY,
            errorFrom(linkOf(jsonOf(payload(network = "main", birthday = testnetOnly))))
        )
    }

    @Test
    fun `rejects an unparseable createdAt`() {
        assertEquals(GiftLinkError.INVALID_CREATED_AT, errorFrom(linkOf(jsonOf(payload(createdAt = "yesterday")))))
    }

    @Test
    fun `accepts an unparseable expiresAt because expiry is advisory`() {
        // Nothing on chain enforces expiry, so a peer's clock or formatting must never be the
        // reason a funded card cannot be claimed.
        val decoded = GiftLinkCodec.decode(linkOf(jsonOf(payload(expiresAt = "whenever"))), ZcashNetwork.Mainnet)

        assertEquals("whenever", decoded.expiresAt)
    }

    @Test
    fun `accepts a message at both limits and rejects one past either`() {
        val atClusterLimit = "😀".repeat(GiftMessage.MAX_GRAPHEMES)
        val overClusterLimit = "😀".repeat(GiftMessage.MAX_GRAPHEMES + 1)
        // One cluster, 601 bytes: a base letter plus 300 combining accents. Isolates the byte
        // bound, which a cluster count cannot stand in for.
        val overByteLimit = "a" + "́".repeat(300)

        assertEquals(GiftMessage.MAX_GRAPHEMES, GiftMessage.graphemeCount(atClusterLimit))
        assertEquals(GiftMessage.MAX_UTF8_BYTES, atClusterLimit.toByteArray().size)
        assertEquals(1, GiftMessage.graphemeCount(overByteLimit))
        assertTrue(overByteLimit.toByteArray().size > GiftMessage.MAX_UTF8_BYTES)

        val atLimitLink = linkOf(jsonOf(payload(message = atClusterLimit)))
        assertEquals(atClusterLimit, GiftLinkCodec.decode(atLimitLink, ZcashNetwork.Mainnet).message)
        assertEquals(GiftLinkError.MESSAGE_TOO_LONG, errorFrom(linkOf(jsonOf(payload(message = overClusterLimit)))))
        assertEquals(GiftLinkError.MESSAGE_TOO_LONG, errorFrom(linkOf(jsonOf(payload(message = overByteLimit)))))
    }

    @Test
    fun `counts grapheme clusters rather than UTF-16 units`() {
        assertEquals(1, GiftMessage.graphemeCount("😀"))
        assertEquals(2, "😀".length)
        assertEquals(3, GiftMessage.graphemeCount("abc"))
        assertEquals(0, GiftMessage.graphemeCount(""))
    }

    @Test
    fun `refuses to encode a payload it would refuse to decode`() {
        assertEquals(GiftLinkError.INVALID_AMOUNT, encodeErrorFrom(payload(amount = "0")))
        assertEquals(GiftLinkError.INVALID_MNEMONIC, encodeErrorFrom(payload(mnemonic = "not a phrase")))
        assertEquals(GiftLinkError.UNSUPPORTED_VERSION, encodeErrorFrom(payload(v = 2)))
    }

    @Test
    fun `verifies the address against the one its mnemonic derives`() {
        GiftLinkCodec.verifyAddressMatches(payload(), ADDRESS)
        GiftLinkCodec.verifyAddressMatches(payload(), "  $ADDRESS  ")

        val error =
            assertFailsWith<GiftLinkException> {
                GiftLinkCodec.verifyAddressMatches(payload(), "u1someotheraddress")
            }
        assertEquals(GiftLinkError.ADDRESS_MISMATCH, error.error)
    }

    @Test
    fun `proceeds silently for a birthday inside the scan window`() {
        assertEquals(GiftBirthdayVerdict.Proceed, GiftLinkCodec.evaluateBirthday(TIP, TIP))
        assertEquals(
            GiftBirthdayVerdict.Proceed,
            GiftLinkCodec.evaluateBirthday(TIP - GiftLinkCodec.SILENT_SCAN_BLOCKS, TIP)
        )
    }

    @Test
    fun `asks for consent rather than clamping an old birthday`() {
        val old = TIP - GiftLinkCodec.SILENT_SCAN_BLOCKS - 1

        // Not clamped, not rejected: clamping past the funding height means the note is never
        // trial-decrypted and a perfectly good card claims empty.
        assertEquals(
            GiftBirthdayVerdict.NeedsConsent(GiftLinkCodec.SILENT_SCAN_BLOCKS + 1),
            GiftLinkCodec.evaluateBirthday(old, TIP)
        )
    }

    @Test
    fun `rejects a birthday above the chain tip`() {
        val error = assertFailsWith<GiftLinkException> { GiftLinkCodec.evaluateBirthday(TIP + 1, TIP) }

        assertEquals(GiftLinkError.BIRTHDAY_ABOVE_TIP, error.error)
    }

    @Test
    fun `names each supported network`() {
        assertEquals("main", GiftLinkCodec.networkName(ZcashNetwork.Mainnet))
        assertEquals("test", GiftLinkCodec.networkName(ZcashNetwork.Testnet))
    }

    @Test
    fun `keeps the mnemonic out of toString`() {
        val rendered = payload().toString()

        assertFalse(rendered.contains("abandon"))
        assertFalse(rendered.contains(ADDRESS))
    }

    private fun errorFrom(link: String, network: ZcashNetwork = ZcashNetwork.Mainnet): GiftLinkError =
        assertFailsWith<GiftLinkException> { GiftLinkCodec.decode(link, network) }.error

    private fun encodeErrorFrom(payload: GiftLinkPayload): GiftLinkError =
        assertFailsWith<GiftLinkException> { GiftLinkCodec.encode(payload) }.error

    private fun linkOf(json: String): String =
        "https://$GIFT_LINK_HOST/c/v1#k=" +
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(json.toByteArray())

    /**
     * Hand-rolled so tests can build payloads the codec would refuse to encode — an unknown
     * version, a missing field, a fifth network.
     */
    private fun jsonOf(payload: GiftLinkPayload): String =
        buildString {
            append("""{"v":${payload.v},""")
            append(""""network":"${payload.network}",""")
            append(""""address":"${payload.address}",""")
            append(""""amountZatoshi":"${payload.amountZatoshi}",""")
            append(""""mnemonic":"${payload.mnemonic.replace("\n", "\\n")}",""")
            append(""""birthdayHeight":${payload.birthdayHeight},""")
            append(""""createdAt":"${payload.createdAt}"""")
            payload.expiresAt?.let { append(""","expiresAt":"$it"""") }
            payload.message?.let { append(""","message":"$it"""") }
            append("}")
        }

    private fun payload(
        v: Int = GiftLinkCodec.VERSION,
        network: String = "main",
        address: String = ADDRESS,
        amount: String = "100000000",
        mnemonic: String = MNEMONIC,
        birthday: Long = BIRTHDAY,
        createdAt: String = "2026-08-20T12:00:00Z",
        expiresAt: String? = null,
        message: String? = null,
    ) = GiftLinkPayload(
        v = v,
        network = network,
        address = address,
        amountZatoshi = amount,
        mnemonic = mnemonic,
        birthdayHeight = birthday,
        createdAt = createdAt,
        expiresAt = expiresAt,
        message = message,
    )

    private companion object {
        /** BIP-39 test vector for all-zero entropy. Never a real wallet. */
        const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon art"

        const val ADDRESS = "u1exampleunifiedaddressforgiftcardtests"
        const val BIRTHDAY = 2_800_000L
        const val TIP = 3_000_000L
    }
}
