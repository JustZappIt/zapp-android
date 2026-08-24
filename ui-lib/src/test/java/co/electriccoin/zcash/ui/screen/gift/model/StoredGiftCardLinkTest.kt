// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift.model

import cash.z.ecc.android.sdk.model.ZcashNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The persisted record has to be able to become a claimable link, every time.
 *
 * This is the invariant the create flow leans on: it encodes the link *before* funding precisely so
 * a record that cannot produce one is caught while the money is still in the sender's wallet. If
 * these break, a funded card is unreachable and there is no reclaim.
 */
class StoredGiftCardLinkTest {
    @Test
    fun `a stored card round trips through a link`() {
        val decoded = GiftLinkCodec.decode(GiftLinkCodec.encode(card().toLinkPayload()), ZcashNetwork.Mainnet)

        assertEquals(MNEMONIC, decoded.mnemonic)
        assertEquals(AMOUNT.toString(), decoded.amountZatoshi)
        assertEquals(BIRTHDAY, decoded.birthdayHeight)
        assertEquals(GiftLinkCodec.VERSION, decoded.v)
    }

    @Test
    fun `carries the optional message and expiry through, and omits them when absent`() {
        val full = card(message = "happy birthday", expiresAt = "2026-12-24T00:00:00Z")
        val bare = card(message = null, expiresAt = null)

        val decodedFull = GiftLinkCodec.decode(GiftLinkCodec.encode(full.toLinkPayload()), ZcashNetwork.Mainnet)
        val decodedBare = GiftLinkCodec.decode(GiftLinkCodec.encode(bare.toLinkPayload()), ZcashNetwork.Mainnet)

        assertEquals("happy birthday", decodedFull.message)
        assertEquals("2026-12-24T00:00:00Z", decodedFull.expiresAt)
        assertEquals(null, decodedBare.message)
        assertEquals(null, decodedBare.expiresAt)
    }

    @Test
    fun `a testnet card encodes as a testnet link`() {
        val link = GiftLinkCodec.encode(card(network = "test").toLinkPayload())

        assertEquals("test", GiftLinkCodec.decode(link, ZcashNetwork.Testnet).network)
        // And a mainnet wallet must refuse it rather than scan for a note that cannot exist.
        assertFailsWithNetworkMismatch { GiftLinkCodec.decode(link, ZcashNetwork.Mainnet) }
    }

    @Test
    fun `the link stays out of the record's toString`() {
        // The record and the payload both hold the bearer phrase. Interpolating either into a log
        // line or a crash report would publish the money.
        val rendered = card().toString()

        assertFalse(rendered.contains(MNEMONIC))
        assertFalse(rendered.contains(ADDRESS))
        assertTrue(rendered.contains("redacted"))
    }

    private fun assertFailsWithNetworkMismatch(block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue(error is GiftLinkException, "expected a GiftLinkException, got $error")
        assertEquals(GiftLinkError.NETWORK_MISMATCH, error.error)
    }

    private fun card(
        network: String = "main",
        message: String? = null,
        expiresAt: String? = null,
    ) = StoredGiftCard(
        id = "6f1c0f6e-0b6b-4f2e-9a5a-6f1c0f6e0b6b",
        network = network,
        address = ADDRESS,
        mnemonic = MNEMONIC,
        amountZatoshi = AMOUNT,
        birthdayHeight = BIRTHDAY,
        sourceAccountUuid = "account-uuid",
        createdAt = "2026-08-20T12:00:00Z",
        updatedAt = "2026-08-20T12:00:00Z",
        status = GiftCardStatus.DRAFT,
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
        const val AMOUNT = 100_000_000L
        const val BIRTHDAY = 2_800_000L
    }
}
