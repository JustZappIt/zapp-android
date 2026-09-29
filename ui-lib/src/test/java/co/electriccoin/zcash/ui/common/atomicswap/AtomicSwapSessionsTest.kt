// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import org.junit.Test
import xyz.justzappit.offramp.atomicswap.AtomicSwapRecord
import xyz.justzappit.offramp.atomicswap.SwapQuote
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AtomicSwapSessionsTest {
    @Test
    fun oldPendingSwapsKeepTheirOriginalContractAndServices() {
        val original = AtomicSwapTestnet.legacy
        assertEquals(original, AtomicSwapSessions.deploymentFor(record(original)))
        assertEquals("http://127.0.0.1:8787", AtomicSwapSessions.deploymentFor(record(original)).config.makerUrl)
        val current = AtomicSwapTestnet.deployment
        assertEquals(current, AtomicSwapSessions.deploymentFor(record(current)))
        assertEquals(ReverseSwapTestnet.deployment.contract, current.config.contract.lowercaseHex)
    }

    @Test
    fun unknownDeploymentsAreNeverMappedToTheCurrentContract() {
        val old = record(AtomicSwapTestnet.legacy)
        assertFailsWith<IllegalStateException> {
            AtomicSwapSessions.deploymentFor(old.copy(quote = old.quote.copy(contract = "0x" + "01".repeat(20))))
        }
    }

    private fun record(deployment: AtomicSwapDeployment): AtomicSwapRecord {
        val config = deployment.config
        return AtomicSwapRecord(
            0,
            SwapQuote(
                "quote",
                "maker",
                "share",
                "proof",
                config.chainId,
                config.contract.lowercaseHex,
                config.token.lowercaseHex,
                "1000000",
                100000,
                1000
            ),
            "swap",
            1,
            1
        )
    }
}
