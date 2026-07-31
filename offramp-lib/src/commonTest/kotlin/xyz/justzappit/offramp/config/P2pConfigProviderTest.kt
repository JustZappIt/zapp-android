// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.config

import kotlin.test.Test
import kotlin.test.assertFailsWith

class P2pConfigProviderTest {
    // Only ship-safety guard worth a dedicated test: mainnet without explicit overrides must fail
    // closed, never quietly fall through to a default RPC. Everything else here was provider-
    // returns-its-constructor-args fluff; trimmed by the audit.
    @Test
    fun `mainnet requires explicit RPC and subgraph overrides`() {
        assertFailsWith<IllegalStateException> {
            P2pConfigProvider(networkName = "mainnet").current()
        }
        assertFailsWith<IllegalStateException> {
            P2pConfigProvider(networkName = "mainnet", rpcUrlOverride = "https://x").current()
        }
    }
}
