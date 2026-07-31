// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.linkpreview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinkPreviewMetadataTest {
    @Test
    fun `parses open graph metadata regardless of attribute order`() {
        val html =
            """
            <html><head>
              <meta content="Zapp &amp; friends" property="og:title">
              <meta name='description' content='Private peer-to-peer messaging'>
              <meta content="Zapp" property="og:site_name">
              <meta property="og:image" content="/social/card.png">
            </head></html>
            """.trimIndent()

        val preview = checkNotNull(parseLinkPreview("https://zapp.example/story", html))

        assertEquals("Zapp & friends", preview.title)
        assertEquals("Private peer-to-peer messaging", preview.description)
        assertEquals("Zapp", preview.siteName)
        assertEquals("https://zapp.example/social/card.png", preview.imageUrl)
    }

    @Test
    fun `falls back to title and host`() {
        val preview = checkNotNull(parseLinkPreview("https://www.example.com/a", "<title> Example page </title>"))

        assertEquals("Example page", preview.title)
        assertEquals("example.com", preview.siteName)
    }

    @Test
    fun `detects links without swallowing sentence punctuation`() {
        val links = detectWebUrls("See https://example.com/page, then http://example.org/test.")

        assertEquals(listOf("https://example.com/page", "http://example.org/test"), links.map { it.url })
        assertEquals("https://example.com/page", firstWebUrl("See https://example.com/page."))
    }

    @Test
    fun `only previews safe public https urls`() {
        assertNull(safePreviewUrl("http://example.com"))
        assertNull(safePreviewUrl("https://localhost/page"))
        assertNull(safePreviewUrl("https://127.0.0.1/page"))
        assertNull(safePreviewUrl("https://[::1]/page"))
        assertNull(safePreviewUrl("https://192.168.1.2/page"))
        assertNull(safePreviewUrl("https://user:password@example.com/page"))
        assertEquals("https://example.com/page", safePreviewUrl("https://example.com/page"))
    }

    @Test
    fun `rejects unsafe metadata images`() {
        val html =
            """
            <meta property="og:title" content="Safe page">
            <meta property="og:image" content="https://127.0.0.1/private.png">
            """.trimIndent()

        val preview = checkNotNull(parseLinkPreview("https://example.com/page", html))

        assertNull(preview.imageUrl)
    }
}
