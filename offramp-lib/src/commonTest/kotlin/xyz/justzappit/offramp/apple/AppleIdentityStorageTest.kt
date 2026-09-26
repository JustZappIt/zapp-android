// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 The Zapp Contributors
package xyz.justzappit.offramp.apple

import kotlinx.coroutines.test.runTest
import xyz.justzappit.offramp.identity.PendingIdentityVerification
import xyz.justzappit.offramp.p2p.CurrencyCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AppleIdentityStorageTest {
    private class Storage : AppleIdentityStorage {
        val records = mutableMapOf<String, String>()
        var failWrites = false

        override fun identityRecord(key: String) = AppleStorageValue(records[key])

        override fun storeIdentityRecord(key: String, value: String?) {
            check(!failWrites)
            if (value == null) records.remove(key) else records[key] = value
        }
    }

    @Test
    fun `recreated Apple adapter retains session and isolates check and wallet keys`() =
        runTest {
            val storage = Storage()
            val pending = PendingIdentityVerification("nonce.BRL", CurrencyCode.Brl, 1800, code = "redeemed-code")
            AppleIdentityStore(storage).set("chain-contract-wallet-Liveness", pending)
            val restored = AppleIdentityStore(storage)
            assertEquals(pending, restored.get("chain-contract-wallet-Liveness"))
            assertNull(restored.get("chain-contract-wallet-Passport"))
            assertNull(restored.get("chain-contract-other-Liveness"))
            restored.set("chain-contract-wallet-Liveness", null)
            assertNull(restored.get("chain-contract-wallet-Liveness"))
        }

    @Test
    fun `host storage failures and corrupt records fail closed`() =
        runTest {
            val storage = Storage()
            val adapter = AppleIdentityStore(storage)
            storage.records["key"] = "corrupted"
            assertFailsWith<Exception> { adapter.get("key") }
            storage.failWrites = true
            assertFailsWith<IllegalStateException> {
                adapter.set("key", PendingIdentityVerification("nonce.INR", CurrencyCode.Inr, 1800))
            }
            assertEquals("corrupted", storage.records["key"])
        }
}
