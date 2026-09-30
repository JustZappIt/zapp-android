// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.ui.common.provider.InMemoryPreferenceProvider
import co.electriccoin.zcash.ui.screen.privateusd.toZec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.atomicswap.ReversePhase
import xyz.justzappit.offramp.atomicswap.ReverseSwapRecord
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals

class ReverseSwapStoreImplTest {
    @Test
    fun `a quote only previewed is the active record but never history`() =
        runTest {
            val store = ReverseSwapStoreImpl(InMemoryPreferenceProvider().encrypted())

            store.save(toZec(0, ReversePhase.QUOTED))
            store.save(toZec(1, ReversePhase.QUOTED))

            assertEquals(1, store.active()?.index)
            assertEquals(emptyList(), store.observeHistory.first())
        }

    @Test
    fun `a conversion the user went ahead with stays in history after the next preview`() =
        runTest {
            val store = ReverseSwapStoreImpl(InMemoryPreferenceProvider().encrypted())

            store.save(toZec(0, ReversePhase.QUOTED))
            store.save(toZec(0, ReversePhase.ACCEPTING))
            store.save(toZec(0, ReversePhase.COMPLETE))
            store.save(toZec(1, ReversePhase.QUOTED))

            assertEquals(listOf(0 to ReversePhase.COMPLETE), store.observeHistory.first().map { it.index to it.phase })
            assertEquals(1, store.active()?.index)
        }

    @Test
    fun `history keeps the latest hundred`() =
        runTest {
            val store = ReverseSwapStoreImpl(InMemoryPreferenceProvider().encrypted())

            repeat(105) { store.save(toZec(it, ReversePhase.CANCELLED)) }

            assertEquals((5 until 105).toList(), store.observeHistory.first().map { it.index })
        }

    @Test
    fun `previews an earlier build kept in history go on the next save`() =
        runTest {
            val preferences = InMemoryPreferenceProvider()
            val kept =
                listOf(toZec(0, ReversePhase.QUOTED), toZec(1, ReversePhase.REFUNDED), toZec(2, ReversePhase.QUOTED))
            val state = JsonObject(mapOf("active" to encode(kept.last()), "history" to JsonArray(kept.map(::encode))))
            preferences.putString(PreferenceKey(KEY), state.toString())
            val store = ReverseSwapStoreImpl(preferences.encrypted())

            store.save(toZec(3, ReversePhase.QUOTED))

            assertEquals(listOf(1), store.observeHistory.first().map { it.index })
        }

    @Test
    fun `a conversion an earlier build kept reads typed and is written back unchanged`() =
        runTest {
            val preferences = InMemoryPreferenceProvider()
            preferences.putString(PreferenceKey(KEY), KEPT)
            val store = ReverseSwapStoreImpl(preferences.encrypted())

            val record = checkNotNull(store.underWay())
            store.save(record)

            assertEquals(TxHash.fromHex("0x" + "08".repeat(32)), record.funding?.txId)
            assertEquals(Usdc6.ofMicros(1_002_506), record.debit)
            assertEquals(KEPT, preferences.getString(PreferenceKey(KEY)))
        }

    private fun encode(record: ReverseSwapRecord) = Json.encodeToJsonElement(record)

    private companion object {
        const val KEY = "reverse_swap_v1"
        private val RECORD =
            """{"index":4,"deployment":{"makerUrl":"https://zecswap-testnet.pepeman931.workers.dev/maker",""" +
                """"relayerUrl":"https://zecswap-testnet.pepeman931.workers.dev/relayer",""" +
                """"rpcUrl":"https://ethereum-sepolia-rpc.publicnode.com","chainId":11155111,""" +
                """"contract":"0xbd9a37f47a988aefc4d80395727f41feb698e225",""" +
                """"token":"0x5764d0044bef5aa839e0ddafe2073421101b9ed8",""" +
                """"railgun":"0xecfcf3b4ec647c4ca6d49108b311b7a7c9543fea",""" +
                """"maker":"0x2bac02b5032e9092493814c705f156b49e288922",""" +
                """"relayer":"0xd9633572041886fa7584a2e12f36c8c7f1126412","maxRefundFee":"100000"},""" +
                """"quote":{"terms":{"quoteId":"0x${"01".repeat(32)}",""" +
                """"maker":"0x2bac02b5032e9092493814c705f156b49e288922",""" +
                """"makerShare":"0x${"02".repeat(64)}","makerProof":"0x${"07".repeat(64)}","chainId":11155111,""" +
                """"contract":"0xbd9a37f47a988aefc4d80395727f41feb698e225",""" +
                """"token":"0x5764d0044bef5aa839e0ddafe2073421101b9ed8","amount":"1000000","depositZat":100000,""" +
                """"expiresAt":1790000300},"user":"0x${"01".repeat(20)}","refundNote":"0x${"06".repeat(32)}",""" +
                """"fundingDeadline":1790000400,"readyDeadline":1790002000,"refundAfter":1790004000},""" +
                """"swapId":"0x${"08".repeat(32)}","userShare":"0x${"01".repeat(64)}",""" +
                """"acceptance":{"userShare":"0x${"01".repeat(64)}","userProof":"0x${"03".repeat(64)}",""" +
                """"viewingKeys":"0x${"04".repeat(64)}"},"birthday":3900000,"account":"${"0a0b".repeat(8)}",""" +
                """"phase":"CONFIRMING_ESCROW","cost":{"debit":"1002506","railgunFee":"2506"},""" +
                """"funding":{"raw":"0x02f8","txId":"0x${"08".repeat(32)}",""" +
                """"cost":{"debit":"1002506","railgunFee":"2506"}}}"""

        /** What the build before the typed records kept for a conversion under way. */
        val KEPT = """{"active":$RECORD,"history":[$RECORD]}"""
    }
}
