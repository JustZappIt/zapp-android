// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.grouplink.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupInviteLinksTest {
    @Test
    fun `recognises both forms and nothing else`() {
        assertTrue(GroupInviteLinks.isGroupLink(LINK))
        assertTrue(GroupInviteLinks.isGroupLink(HANDOFF))
        assertTrue(GroupInviteLinks.isGroupLink("HTTPS://Join.JustZappIt.xyz/g/v1#$PAYLOAD"))
        // A later version is still ours to route, so the person hears "update Zapp" and not silence.
        assertTrue(GroupInviteLinks.isGroupLink("https://join.justzappit.xyz/g/v2#abc"))

        assertFalse(GroupInviteLinks.isGroupLink("https://gift.justzappit.xyz/c/v1#abc"))
        assertFalse(GroupInviteLinks.isGroupLink("https://join.justzappit.xyz/c/v1#abc"))
        assertFalse(GroupInviteLinks.isGroupLink("https://join.justzappit.xyz.evil.example/g/v1#abc"))
        assertFalse(GroupInviteLinks.isGroupLink("http://join.justzappit.xyz/g/v1#abc"))
        assertFalse(GroupInviteLinks.isGroupLink("zcash:u1abc"))
        assertFalse(GroupInviteLinks.isGroupLink("xyz.justzappit.zapp://gift/v1/abc"))
    }

    @Test
    fun `canonical drops the query and keeps the fragment`() {
        assertEquals(LINK, GroupInviteLinks.canonical("https://join.justzappit.xyz/g/v1?utm_source=x&s=1#$PAYLOAD"))
        assertEquals(LINK, GroupInviteLinks.canonical("https://JOIN.justzappit.xyz/g/v1#$PAYLOAD"))
        assertEquals(HANDOFF, GroupInviteLinks.canonical("$HANDOFF?from=page"))
    }

    @Test
    fun `canonical refuses what is not a link or is too long to be an honest one`() {
        assertNull(GroupInviteLinks.canonical("https://gift.justzappit.xyz/c/v1#abc"))
        assertNull(GroupInviteLinks.canonical("https://join.justzappit.xyz/g/v1#" + "A".repeat(GroupInviteLinks.MAX_LENGTH)))
    }

    @Test
    fun `finds a link in pasted text`() {
        assertEquals(LINK, GroupInviteLinks.fromPastedText("  $LINK\n"))
        assertEquals(LINK, GroupInviteLinks.fromPastedText("Join us here ($LINK)."))
        assertNull(GroupInviteLinks.fromPastedText("no link in here"))
        assertNull(GroupInviteLinks.fromPastedText(""))
    }

    companion object {
        // The "minimal" vector from zappmessaging-sdk test/group-link-vectors.json.
        const val PAYLOAD = "AQAAAQIDBAUGBwgJCgsMDQ4P0EqyMnQrtKs6E2i9RhXk5tAiSrcaAWuvhSCjMsl3hzcR765S"
        const val LINK = "https://join.justzappit.xyz/g/v1#$PAYLOAD"
        const val HANDOFF = "xyz.justzappit.zapp://g/v1/$PAYLOAD"
    }
}
