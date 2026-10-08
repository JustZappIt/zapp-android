// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.ui.common.provider.InMemoryPreferenceProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.railgun.RailgunAddress
import xyz.justzappit.railgun.RailgunDestination
import xyz.justzappit.railgun.RailgunSignedTransaction
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrivateUsdSendLogTest {
    private val preferences = InMemoryPreferenceProvider()
    private val log = PrivateUsdSendLog(preferences.encrypted())

    @Test
    fun `a confirmed send reads back as the log keeps it`() =
        runTest {
            preferences.putString(KEY, """{"sends":[$CONFIRMED_SEND]}""")

            val history = log.observe.first()

            assertEquals(
                PrivateUsdSendRecord(
                    txHash = TxHash.fromHex("0x" + "ab".repeat(32)),
                    token = TOKEN,
                    amount = BigInteger.valueOf(1_000_000),
                    to = RailgunDestination.Public(Address.parse("0x1c7f9a756b08753cf8da94d394659134bb8c5539")),
                    sentAt = 1_700_000_000,
                ),
                history.sends.single(),
            )
            assertTrue(history.sends.single().confirmed)
            assertTrue(history.sends.single().withdraw)
            assertEquals(emptyList(), history.pending)
        }

    @Test
    fun `a send goes from proving, to signed and kept before it goes out, to confirmed`() =
        runTest {
            log.begin(PENDING)
            assertEquals(listOf(PENDING), log.observe.first().pending)

            log.sign(PENDING, SIGNED, at = 20)
            val signed = log.observe.first()
            assertEquals(emptyList(), signed.pending)
            assertEquals(PENDING.signed(SIGNED, 20), signed.sends.single())
            assertEquals(
                SIGNED.raw,
                signed.sends
                    .single()
                    .signed
                    ?.raw
            )
            assertEquals(signed.sends, log.unconfirmed())

            log.confirm(TX_HASH)
            val confirmed =
                log.observe
                    .first()
                    .sends
                    .single()
            assertTrue(confirmed.confirmed)
            assertNull(confirmed.signed)
        }

    @Test
    fun `a send that moved nothing leaves nothing behind`() =
        runTest {
            preferences.putString(KEY, """{"sends":[$CONFIRMED_SEND]}""")
            log.begin(PENDING)
            log.sign(PENDING, SIGNED, at = 20)

            log.remove(PENDING, TX_HASH)

            val history = log.observe.first()
            assertEquals(listOf(TxHash.fromHex("0x" + "ab".repeat(32))), history.sends.map { it.txHash })
            assertEquals(emptyList(), history.pending)
        }

    @Test
    fun `a send another process left proving never went out, and is dropped`() =
        runTest {
            val stale =
                """{"id":"old","token":"${TOKEN.checksumHex}","amount":"1",""" +
                    """"to":"${PRIVATE.address.value}","startedAt":1}"""
            preferences.putString(KEY, """{"sends":[],"pending":[$stale]}""")

            assertEquals(emptyList(), log.observe.first().pending)

            log.begin(PENDING)
            assertEquals(
                listOf("pending-1"),
                log.observe
                    .first()
                    .pending
                    .map { it.id }
            )
            assertFalse(checkNotNull(preferences.getString(KEY)).contains("\"old\""))
        }

    private companion object {
        val KEY = PreferenceKey("private_usd_sends_v1")
        val TX_HASH = TxHash.fromHex("0x" + "cd".repeat(32))
        val TOKEN = Address.parse("0x5764D0044bef5AA839E0dDafE2073421101B9Ed8")
        val PRIVATE =
            RailgunDestination.Private(
                RailgunAddress(
                    "0zk1qyrs4qyrd08p6uep0fc2y8njktgcpezts3rpaq6q0ln948ecjkw8prv7j6f" +
                        "e3z53llz8ursderja0juwv5pgnv8x5klmmwkv8q38h9n704h4d4qjyw7n5qk68nx"
                )
            )
        val CONFIRMED_SEND =
            """{"txHash":"0x${"ab".repeat(32)}","token":"0x5764d0044bef5aa839e0ddafe2073421101b9ed8",""" +
                """"amount":"1000000","to":"0x1c7f9a756b08753cf8da94d394659134bb8c5539","sentAt":1700000000}"""
        val PENDING =
            PrivateUsdPendingSend(
                id = "pending-1",
                token = TOKEN,
                amount = BigInteger.valueOf(2_500_000),
                to = PRIVATE,
                startedAt = 10,
            )
        val SIGNED =
            RailgunSignedTransaction(
                raw = "0x02",
                txHash = TX_HASH,
                from = Address.parse("0x09ed1f966745be18c711c346242c0974dad7c3e5"),
                nonce = 7,
            )
    }
}
