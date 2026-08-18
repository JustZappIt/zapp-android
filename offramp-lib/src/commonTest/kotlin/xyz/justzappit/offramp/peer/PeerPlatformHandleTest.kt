// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The curator hashes the handle verbatim, so normalising is the only canonicalisation in the path
 * and a buyer pays whatever comes out of here. Cases pinned against the curator's own answers.
 */
class PeerPlatformHandleTest {
    @Test
    fun `a chime sign keeps one leading marker however many were typed`() {
        assertEquals("\$andrew", normalize(PeerPlatform.CHIME, "andrew"))
        assertEquals("\$andrew", normalize(PeerPlatform.CHIME, " \$Andrew "))
        assertEquals("\$andrew", normalize(PeerPlatform.CHIME, "\$\$andrew"))
    }

    @Test
    fun `chime signs the curator accepts are not ruled out here`() {
        listOf("\$a", "\$" + "a".repeat(80), "\$and.rew", "\$and-rew", "\$and rew")
            .forEach { assertTrue(accepts(PeerPlatform.CHIME, it), it) }
    }

    @Test
    fun `a bare chime marker is not a sign`() {
        assertFalse(accepts(PeerPlatform.CHIME, "\$"))
    }

    @Test
    fun `zelle lowercases an email`() {
        assertEquals("alice@example.com", normalize(PeerPlatform.ZELLE, " Alice@Example.COM "))
    }

    /**
     * `keccak256(revtag) == payeeDetailsHash` on all 30 live payees sampled, so the typed string is
     * the preimage. A capital would fund a deposit whose payee no proof can ever reproduce.
     */
    @Test
    fun `a revtag is lowercased and loses a typed at-sign`() {
        assertEquals("andrew1abc", normalize(PeerPlatform.REVOLUT, " Andrew1abc "))
        assertEquals("andrew1abc", normalize(PeerPlatform.REVOLUT, "@andrew1abc"))
    }

    @Test
    fun `a revtag with an underscore is not ruled out`() {
        assertTrue(accepts(PeerPlatform.REVOLUT, "andrew_abc"))
    }

    /**
     * The one rail whose case is deliberately left alone, and lowercasing it to match the others
     * would be a rule with a false reason behind it.
     *
     * Monzo is the only platform whose payee hash is not taken over the handle. The curator resolves
     * the monzo.me page server-side and hashes the Monzo user id it finds there, which is what a
     * buyer's proof carries: `keccak256(user id) == payeeDetailsHash` on the live payees sampled,
     * where `keccak256(handle)` matches none of them. monzo.me resolves the username
     * case-insensitively to the same id, so the case a user types never reaches the hash.
     */
    @Test
    fun `a monzo username keeps the case it was typed in`() {
        assertEquals("TheMoneyTree", normalize(PeerPlatform.MONZO, "  TheMoneyTree "))
        assertTrue(accepts(PeerPlatform.MONZO, "TheMoneyTree"))
        assertTrue(accepts(PeerPlatform.MONZO, "themoneytree"))
    }

    @Test
    fun `zelle takes an email and not a phone number the curator would refuse`() {
        assertTrue(accepts(PeerPlatform.ZELLE, "alice@example.com"))
        listOf("2125551234", "+12125551234", "12125551234", "+1-212-555-1234")
            .forEach { assertFalse(accepts(PeerPlatform.ZELLE, it), it) }
    }

    private fun normalize(platform: PeerPlatform, raw: String) = platform.normalizeHandle(raw).value

    private fun accepts(platform: PeerPlatform, raw: String) =
        runCatching { platform.normalizeHandle(raw) }
            .getOrNull()
            ?.let(platform::hasPlausibleFormat) == true
}
