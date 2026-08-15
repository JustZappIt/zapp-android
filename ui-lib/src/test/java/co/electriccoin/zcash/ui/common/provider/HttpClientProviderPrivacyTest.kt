// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.provider

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpClientProviderPrivacyTest {
    @Test
    fun `wallet-bearing query parameters are redacted from debug logs`() {
        val message =
            "GET https://example.test/status?depositAddress=0xsecret&recipient=u1secret" +
                "&refundTo=0xrefund&refundAddress=0xother&safe=value"

        val sanitized = sanitizeHttpLogMessage(message)

        assertFalse(sanitized.contains("0xsecret"))
        assertFalse(sanitized.contains("u1secret"))
        assertFalse(sanitized.contains("0xrefund"))
        assertFalse(sanitized.contains("0xother"))
        assertTrue(sanitized.contains("safe=value"))
    }
}
